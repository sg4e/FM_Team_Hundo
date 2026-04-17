/*
 * Copyright (C) 2025 sg4e
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

use serde::{Deserialize};
use reqwest::Client;
use std::error::Error;
use std::fs::File;
use std::process;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio::time::{interval, Duration};
use tokio::select;

mod cards;

const EMU_MESSAGE_BUFFER_SIZE: usize = 100;
const CREDENTIALS_FILENAME: &str = "credentials_FM_Team_Hundo.json";

#[derive(Debug, Deserialize)]
struct EmuMessage {
    #[serde(rename = "type")]
    message_type: String,
    value: i32
}

#[derive(Debug, Deserialize)]
struct Credentials {
    key: String,
    url: String,
    username: String
}

#[derive(Debug, Deserialize)]
struct ApiResponse {
    result: String,
    message: Option<String>
}

async fn handle_connection(mut stream: TcpStream, sender: &mpsc::Sender<String>) -> Result<(), Box<dyn Error>> {
    let (reader, _) = stream.split();
    let mut reader = BufReader::new(reader);
    let mut line = String::new();

    println!("Client connected");

    loop {
        line.clear();
        let bytes_read = reader.read_line(&mut line).await?;
        
        if bytes_read == 0 {
            println!("Client disconnected");
            break;
        }

        sender.send(line.clone()).await.unwrap();
    }

    Ok(())
}

async fn consume_cards(mut receiver: mpsc::Receiver<String>) -> Result<(), Box<dyn Error + Send + Sync>> {
    // start by validating credentials
    let credential_file = match File::open(CREDENTIALS_FILENAME) {
        Ok(file) => file,
        Err(e) => {
            eprintln!("Error opening credentials file: {}", e);
            process::exit(1);
        }
    };
    let credentials: Credentials = match serde_json::from_reader(std::io::BufReader::new(credential_file)) {
        Ok(json) => json,
        Err(e) => {
            eprintln!("Error parsing credentials file: {}", e);
            process::exit(1);
        }
    };
    let http_client = Client::new();
    let send_request = match http_client
            .get(credentials.url + "/validate")
            .header("X-API-Key", credentials.key)
            .send()
            .await {
                Ok(req) => req,
                Err(e) => {
                    eprintln!("Error sending validation request: {}", e);
                    process::exit(1);
            }
    };
    let validation_response = match send_request.text().await {
        Ok(text) => text,
        Err(e) => {
            eprintln!("Error receiving validation response: {}", e);
            process::exit(1);
        }
    };
    let validation_parsed: ApiResponse = match serde_json::from_str(&validation_response) {
        Ok(res) => res,
        Err(e) => {
            eprintln!("Error parsing validation response from server: {}", e);
            eprintln!("Response was: {}", validation_response);
            process::exit(1);
        }
    };
    match validation_parsed.result.as_str() {
        "ok" =>  {
            println!("Successfully connected to FM Hundo website");
            match validation_parsed.message {
                Some(message) => {
                    if message == credentials.username {
                        println!("Welcome, {}.", message);
                    }
                    else {
                        println!("WARNING: Username provided by server does not match username in credentials file. This may cause issues with card tracking.");
                    }
                },
                None => println!("WARNING: No username provided by server. This may cause issues with card tracking.")
            }
        }
        "error" => {
            println!("Validation failed: {}", match validation_parsed.message {
                Some(message) => message,
                None => "Unspecified error".to_string()
            });
            process::exit(1);
        }
        _ => {
            println!("Unexpected response from server: {}", validation_parsed.result);
            process::exit(1);
        }
    };

    let mut buffer: Vec<String> = Vec::with_capacity(EMU_MESSAGE_BUFFER_SIZE);
    while !receiver.is_closed() {
        receiver.recv_many(&mut buffer, EMU_MESSAGE_BUFFER_SIZE).await;
        for raw_message in &buffer {
            match serde_json::from_str::<EmuMessage>(&raw_message) {
                Ok(message) => {
                    match message.message_type.as_str() {
                        "starchips" => println!("Starchips now at {}", message.value),
                        _ => {
                            let card_name: &str = cards::get_card_name_for_id(message.value).unwrap_or("Invalid card");
                            println!("Card from {}: {}", message.message_type, card_name);
                        }
                    };
                }
                Err(e) => {
                    eprintln!("Error parsing JSON: {}", e);
                }
            }
            // TODO: forward raw_message to collection server
        }
        buffer.clear();
    }

    Ok(())
}

async fn listen_for_emu_connection(sender: mpsc::Sender<String>) -> Result<(), Box<dyn Error + Send + Sync>> {
    let port = 51155;
    let listener = TcpListener::bind(format!("127.0.0.1:{port}")).await?;
    println!("Listening on 127.0.0.1:{port} (single connection at a time)");

    let mut interval = interval(Duration::from_secs(10));

    loop {
        select! {
            // Wait for a new connection
            accepted = listener.accept() => {
                match accepted {
                    Ok((stream, addr)) => {
                        println!("Accepted connection from: {}", addr);
                        
                        // Handle this connection (this will block until client disconnects)
                        if let Err(e) = handle_connection(stream, &sender).await {
                            eprintln!("Error handling connection: {}", e);
                        }
                        
                        println!("Ready for new connection...");
                    }
                    Err(e) => {
                        eprintln!("Error accepting connection: {}", e);
                        // Continue listening despite the error
                    }
                }
            }
            _ = interval.tick() => {
                println!("Waiting for connection from emulator...");
            }
        }
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error + Send + Sync>> {
    let (sender, receiver) = mpsc::channel(EMU_MESSAGE_BUFFER_SIZE);

    let emu_server = tokio::spawn(listen_for_emu_connection(sender));  // producer
    let http_poster = tokio::spawn(consume_cards(receiver));  // consumer

    let (emu_result, http_result) = tokio::join!(emu_server, http_poster);
    emu_result??;
    http_result??;

    Ok(())
}
