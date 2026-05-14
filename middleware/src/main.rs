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

use clap::{crate_name, crate_version};
use reqwest::Client;
use serde::Deserialize;
use std::error::Error;
use std::fs::File;
use std::process;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::select;
use tokio::sync::mpsc;
use tokio::time::{Duration, interval, sleep};

mod cards;

const EMU_MESSAGE_BUFFER_SIZE: usize = 1000;
const RETRY_INTERVAL_SECONDS: u64 = 15;
const CREDENTIALS_FILENAME: &str = "credentials_FM_Team_Hundo.json";
const LATEST_RELEASE_URL: &str = "https://github.com/sg4e/FM_Team_Hundo/releases/latest";
const MIDDLEWARE_PROTOCOL_VERSION: Option<&str> = option_env!("FM_HUNDO_PROTOCOL_VERSION");

#[derive(Debug, Deserialize)]
struct EmuMessage {
    #[serde(rename = "type")]
    message_type: String,
    value: i32,
}

#[derive(Debug, Deserialize)]
struct Credentials {
    key: String,
    url: String,
    username: String,
}

#[derive(Debug, Deserialize)]
struct ApiResponse {
    result: String,
    message: Option<String>,
    protocol_version: Option<String>,
}

struct StartupContext {
    credentials: Credentials,
    http_client: Client,
    server_protocol_version: Option<String>,
}

#[derive(Debug, Deserialize, PartialEq)]
struct PluginHello {
    #[serde(rename = "type")]
    message_type: String,
    protocol_version: Option<String>,
}

#[derive(Debug, PartialEq)]
enum ProtocolCheck {
    Match,
    Skipped(&'static str),
    Mismatch,
}

fn stamped_version(version: Option<&str>) -> Option<&str> {
    version.and_then(|value| {
        let trimmed = value.trim();
        if trimmed.is_empty() {
            None
        } else {
            Some(trimmed)
        }
    })
}

fn print_mismatch(component: &str, expected: &str, actual: &str) {
    eprintln!("ERROR: {component} protocol version mismatch.");
    eprintln!("Expected protocol_version {expected}, but {component} reported {actual}.");
    eprintln!("Please download the latest FM Team Hundo release from {LATEST_RELEASE_URL}");
}

fn warn_skipped(reason: &str) {
    eprintln!("WARNING: Protocol version check skipped: {reason}.");
}

fn evaluate_protocol_versions(
    component_protocol_version: Option<&str>,
    server_protocol_version: Option<&str>,
    middleware_protocol_version: Option<&str>,
) -> ProtocolCheck {
    if stamped_version(middleware_protocol_version).is_none() {
        return ProtocolCheck::Skipped("this FM_Sentinel build is unstamped");
    }
    let Some(server_version) = stamped_version(server_protocol_version) else {
        return ProtocolCheck::Skipped("the server did not provide protocol_version");
    };
    let Some(component_version) = stamped_version(component_protocol_version) else {
        return ProtocolCheck::Skipped("the component did not provide protocol_version");
    };
    if component_version == server_version {
        ProtocolCheck::Match
    } else {
        ProtocolCheck::Mismatch
    }
}

fn validate_middleware_protocol(server_protocol_version: Option<&str>) {
    match evaluate_protocol_versions(
        MIDDLEWARE_PROTOCOL_VERSION,
        server_protocol_version,
        MIDDLEWARE_PROTOCOL_VERSION,
    ) {
        ProtocolCheck::Skipped(reason) => warn_skipped(reason),
        ProtocolCheck::Mismatch => {
            print_mismatch(
                "FM_Sentinel",
                stamped_version(server_protocol_version).unwrap(),
                stamped_version(MIDDLEWARE_PROTOCOL_VERSION).unwrap(),
            );
            process::exit(1);
        }
        ProtocolCheck::Match => println!(
            "Protocol version {} matches server",
            stamped_version(MIDDLEWARE_PROTOCOL_VERSION).unwrap()
        ),
    }
}

fn validate_plugin_protocol(
    plugin_protocol_version: Option<&str>,
    server_protocol_version: Option<&str>,
) {
    match evaluate_protocol_versions(
        plugin_protocol_version,
        server_protocol_version,
        MIDDLEWARE_PROTOCOL_VERSION,
    ) {
        ProtocolCheck::Skipped("the component did not provide protocol_version") => {
            warn_skipped("the emulator plugin did not provide protocol_version");
        }
        ProtocolCheck::Skipped(reason) => warn_skipped(reason),
        ProtocolCheck::Mismatch => {
            print_mismatch(
                "emulator plugin",
                stamped_version(server_protocol_version).unwrap(),
                stamped_version(plugin_protocol_version).unwrap(),
            );
            process::exit(1);
        }
        ProtocolCheck::Match => println!(
            "Emulator plugin protocol version {} matches server",
            stamped_version(plugin_protocol_version).unwrap()
        ),
    }
}

fn parse_plugin_hello(raw_message: &str) -> Result<Option<PluginHello>, serde_json::Error> {
    let hello: PluginHello = serde_json::from_str(raw_message)?;
    if hello.message_type == "hello" {
        Ok(Some(hello))
    } else {
        Ok(None)
    }
}

async fn handle_connection(
    mut stream: TcpStream,
    sender: &mpsc::Sender<String>,
    server_protocol_version: Option<&str>,
) -> Result<(), Box<dyn Error>> {
    let (reader, _) = stream.split();
    let mut reader = BufReader::new(reader);
    let mut line = String::new();

    println!("Client connected");
    let mut first_message = true;

    loop {
        line.clear();
        let bytes_read = reader.read_line(&mut line).await?;

        if bytes_read == 0 {
            println!("Client disconnected");
            break;
        }

        if first_message {
            first_message = false;
            match parse_plugin_hello(&line) {
                Ok(Some(hello)) => {
                    validate_plugin_protocol(
                        hello.protocol_version.as_deref(),
                        server_protocol_version,
                    );
                    continue;
                }
                Ok(None) => {
                    warn_skipped("the emulator plugin did not send a version handshake");
                }
                Err(_) => {
                    warn_skipped("the emulator plugin did not send a readable version handshake");
                }
            }
        }

        sender.send(line.clone()).await.unwrap();
    }

    Ok(())
}

async fn validate_startup() -> StartupContext {
    let credential_file = match File::open(CREDENTIALS_FILENAME) {
        Ok(file) => file,
        Err(e) => {
            eprintln!("Error opening credentials file: {}", e);
            process::exit(1);
        }
    };
    let credentials: Credentials =
        match serde_json::from_reader(std::io::BufReader::new(credential_file)) {
            Ok(json) => json,
            Err(e) => {
                eprintln!("Error parsing credentials file: {}", e);
                process::exit(1);
            }
        };
    let http_client = Client::new();
    let send_request = match http_client
        .get(format!("{}{}", credentials.url, "/validate"))
        .header("X-API-Key", &credentials.key)
        .send()
        .await
    {
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
        "ok" => {
            println!("Successfully connected to FM Hundo website");
            match validation_parsed.message {
                Some(message) => {
                    if message == credentials.username {
                        println!("Welcome, {}.", message);
                    } else {
                        println!(
                            "WARNING: Username provided by server does not match username in credentials file. This may cause issues with card tracking."
                        );
                    }
                }
                None => println!(
                    "WARNING: No username provided by server. This may cause issues with card tracking."
                ),
            }
            validate_middleware_protocol(validation_parsed.protocol_version.as_deref());
        }
        "error" => {
            println!(
                "Validation failed: {}",
                match validation_parsed.message {
                    Some(message) => message,
                    None => "Unspecified error".to_string(),
                }
            );
            process::exit(1);
        }
        _ => {
            println!(
                "Unexpected response from server: {}",
                validation_parsed.result
            );
            process::exit(1);
        }
    };

    StartupContext {
        credentials,
        http_client,
        server_protocol_version: validation_parsed.protocol_version,
    }
}

async fn consume_cards(
    mut receiver: mpsc::Receiver<String>,
    credentials: Credentials,
    http_client: Client,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    let mut buffer: Vec<String> = Vec::with_capacity(EMU_MESSAGE_BUFFER_SIZE);
    while !receiver.is_closed() {
        receiver
            .recv_many(&mut buffer, EMU_MESSAGE_BUFFER_SIZE)
            .await;
        for raw_message in &buffer {
            match serde_json::from_str::<EmuMessage>(&raw_message) {
                Ok(message) => {
                    match message.message_type.as_str() {
                        "starchips" => println!("Starchips now at {}", message.value),
                        _ => {
                            let card_name: &str = cards::get_card_name_for_id(message.value)
                                .unwrap_or("Invalid card");
                            println!("Card from {}: {}", message.message_type, card_name);
                        }
                    };
                }
                Err(e) => {
                    eprintln!("Error parsing JSON: {}", e);
                }
            }
        }
        // Forward emu messages to collection server
        if !buffer.is_empty() {
            let messages = format!("[{}]", buffer.join(","));
            let server_response = http_client
                .post(format!("{}{}", credentials.url, "/update"))
                .header("X-API-Key", &credentials.key)
                .header("Content-Type", "application/json")
                .body(messages)
                .send()
                .await;
            match server_response {
                Ok(resp) => {
                    let status = resp.status();
                    match resp.text().await {
                        Ok(text) => {
                            let api_response: ApiResponse = match serde_json::from_str(&text) {
                                Ok(res) => res,
                                Err(e) => {
                                    eprintln!("Error parsing API response: {}", e);
                                    eprintln!("HTTP code was: {}", status);
                                    eprintln!("Response was: {}", text);
                                    return Ok(());
                                }
                            };
                            match api_response.result.as_str() {
                                "ok" => (),
                                "error" => eprintln!(
                                    "Server returned error: {}",
                                    match api_response.message {
                                        Some(message) => message,
                                        None => "Unspecified error".to_string(),
                                    }
                                ),
                                _ => eprintln!(
                                    "Unexpected response from server: {}",
                                    api_response.result
                                ),
                            }
                        }
                        Err(e) => eprintln!("Error reading API response text: {}", e),
                    }
                    buffer.clear();
                }
                Err(e) => {
                    eprintln!(
                        "Error connecting to collection server: {}\nWaiting {} seconds and then trying again...",
                        e, RETRY_INTERVAL_SECONDS
                    );
                    sleep(Duration::from_secs(RETRY_INTERVAL_SECONDS)).await;
                }
            }
        }
    }

    Ok(())
}

async fn listen_for_emu_connection(
    sender: mpsc::Sender<String>,
    server_protocol_version: Option<String>,
) -> Result<(), Box<dyn Error + Send + Sync>> {
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
                        if let Err(e) = handle_connection(stream, &sender, server_protocol_version.as_deref()).await {
                            eprintln!("Error handling connection: {}", e);
                        }

                        println!("Ready for new connection...");
                    }
                    Err(e) => {
                        eprintln!("Error accepting connection: {}", e);
                        // Continue listening despite the error
                    }
                }
                interval.reset();
            }
            _ = interval.tick() => {
                println!("Waiting for connection from emulator...");
            }
        }
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error + Send + Sync>> {
    println!("{}: version {}", crate_name!(), crate_version!());
    match stamped_version(MIDDLEWARE_PROTOCOL_VERSION) {
        Some(version) => println!("FM_Sentinel protocol version: {version}"),
        None => println!("FM_Sentinel protocol version: unstamped local build"),
    }

    let startup_context = validate_startup().await;
    let (sender, receiver) = mpsc::channel(EMU_MESSAGE_BUFFER_SIZE);

    let emu_server = tokio::spawn(listen_for_emu_connection(
        sender,
        startup_context.server_protocol_version,
    )); // producer
    let http_poster = tokio::spawn(consume_cards(
        receiver,
        startup_context.credentials,
        startup_context.http_client,
    )); // consumer

    let (emu_result, http_result) = tokio::join!(emu_server, http_poster);
    emu_result??;
    http_result??;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_plugin_hello_with_protocol_version() {
        let hello = parse_plugin_hello("{\"type\":\"hello\",\"protocol_version\":\"1\"}\n")
            .unwrap()
            .unwrap();

        assert_eq!(hello.message_type, "hello");
        assert_eq!(hello.protocol_version.as_deref(), Some("1"));
    }

    #[test]
    fn parse_plugin_hello_without_protocol_version() {
        let hello = parse_plugin_hello("{\"type\":\"hello\"}\n")
            .unwrap()
            .unwrap();

        assert_eq!(hello.protocol_version, None);
    }

    #[test]
    fn parse_legacy_first_event_as_not_hello() {
        let hello = parse_plugin_hello("{\"type\":\"drop\",\"value\":122}\n").unwrap();

        assert_eq!(hello, None);
    }

    #[test]
    fn exact_protocol_versions_match() {
        assert_eq!(stamped_version(Some("1")), Some("1"));
    }

    #[test]
    fn blank_protocol_version_is_unstamped() {
        assert_eq!(stamped_version(Some("   ")), None);
        assert_eq!(stamped_version(None), None);
    }

    #[test]
    fn matching_protocol_versions_pass() {
        assert_eq!(
            evaluate_protocol_versions(Some("1"), Some("1"), Some("1")),
            ProtocolCheck::Match
        );
    }

    #[test]
    fn mismatching_protocol_versions_fail() {
        assert_eq!(
            evaluate_protocol_versions(Some("2"), Some("1"), Some("1")),
            ProtocolCheck::Mismatch
        );
    }

    #[test]
    fn missing_server_version_skips_check() {
        assert_eq!(
            evaluate_protocol_versions(Some("1"), None, Some("1")),
            ProtocolCheck::Skipped("the server did not provide protocol_version")
        );
    }

    #[test]
    fn missing_middleware_version_skips_check() {
        assert_eq!(
            evaluate_protocol_versions(Some("1"), Some("1"), None),
            ProtocolCheck::Skipped("this FM_Sentinel build is unstamped")
        );
    }

    #[test]
    fn missing_plugin_version_skips_check() {
        assert_eq!(
            evaluate_protocol_versions(None, Some("1"), Some("1")),
            ProtocolCheck::Skipped("the component did not provide protocol_version")
        );
    }
}
