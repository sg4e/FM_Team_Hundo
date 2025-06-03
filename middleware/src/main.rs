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

use serde::Deserialize;
use std::error::Error;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;

mod cards;

const EMU_MESSAGE_BUFFER_SIZE: usize = 100;

#[derive(Debug, Deserialize)]
struct EmuMessage {
    #[serde(rename = "type")]
    message_type: String,
    value: i32
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
    let listener = TcpListener::bind("127.0.0.1:8080").await?;
    println!("Listening on 127.0.0.1:8080 (single connection at a time)");

    loop {
        // Wait for a new connection
        match listener.accept().await {
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
