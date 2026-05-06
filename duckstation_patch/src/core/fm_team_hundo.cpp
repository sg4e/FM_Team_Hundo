// SPDX-License-Identifier: CC-BY-NC-ND-4.0

#include "fm_team_hundo.h"

#include "cpu_core.h"
#include "system.h"

#include "common/log.h"

#include "util/sockets.h"

#include "fmt/format.h"

#include <array>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <unordered_set>

LOG_CHANNEL(FMTeamHundo);

namespace FMTeamHundo {

namespace {

enum class JsonType
{
  Drop,
  Fuse,
  Ritual,
};

class ClientSocket final : public BufferedStreamSocket
{
public:
  ClientSocket(SocketMultiplexer& multiplexer, SocketDescriptor descriptor);
  ~ClientSocket() override;

protected:
  void OnConnected() override;
  void OnDisconnected(const Error& error) override;
  void OnRead() override;
};

struct MemoryElement
{
  u32 address;
  u8 length;
  u32 last = 0;
  u32 now = 0;

  void Update()
  {
    std::array<u8, 4> bytes = {};
    if (!CPU::SafeReadMemoryBytes(address, bytes.data(), length))
      return;

    u32 value = 0;
    for (u32 i = 0; i < length; i++)
      value |= (ZeroExtend32(bytes[i]) << (i * 8));

    last = now;
    now = value;
  }

  void Initialize()
  {
    last = now;
  }

  bool HasChanged() const
  {
    return (last != now);
  }

  bool HasIncreased() const
  {
    return (now > last);
  }
};

struct State
{
  std::shared_ptr<ClientSocket> client;
  std::unordered_set<u32> ritual_ids_sent;
  u32 frames_until_reconnect = 0;
  bool warned_about_connection = false;
};

constexpr std::string_view SERVER_IP = "127.0.0.1";
constexpr u16 SERVER_PORT = 51155;
constexpr u32 RECONNECT_INTERVAL_FRAMES = 300;

constexpr std::array<u8, 20> VERIFICATION_BYTES = {
  0x42, 0x41, 0x53, 0x4C, 0x55, 0x53, 0x2D, 0x30, 0x31, 0x34,
  0x31, 0x31, 0x2D, 0x59, 0x55, 0x47, 0x49, 0x4F, 0x48, 0x00,
};

constexpr std::array<u32, 21> RITUAL_MONSTER_IDS = {
  356, 357, 360, 362, 364, 365, 374, 380, 701, 702, 703,
  704, 705, 706, 708, 710, 715, 716, 718, 719, 720,
};

MemoryElement s_fuse_equip_ritual_memory{0xEA118, 2};
MemoryElement s_drop_memory{0x1D56A8, 2};
MemoryElement s_starchip_memory{0x1D07E0, 4};
MemoryElement s_rng_memory{0x0FE6F8, 4};
MemoryElement s_menu_id_memory{0x184594, 1};
MemoryElement s_player_fusion_count{0x0E9FF8, 1};
MemoryElement s_opponent_id_memory{0x09B361, 1};

std::array<MemoryElement, 5> s_player_monsters = {
  MemoryElement{0x1A7B70, 2},
  MemoryElement{0x1A7B8C, 2},
  MemoryElement{0x1A7BA8, 2},
  MemoryElement{0x1A7BC4, 2},
  MemoryElement{0x1A7BE0, 2},
};

std::array<MemoryElement*, 11> s_fm_memory = {
  &s_fuse_equip_ritual_memory,
  &s_drop_memory,
  &s_starchip_memory,
  &s_rng_memory,
  &s_player_fusion_count,
  &s_opponent_id_memory,
  &s_player_monsters[0],
  &s_player_monsters[1],
  &s_player_monsters[2],
  &s_player_monsters[3],
  &s_player_monsters[4],
};

State s_state;

bool IsRitualMonster(const u32 id)
{
  for (const u32 ritual_id : RITUAL_MONSTER_IDS)
  {
    if (ritual_id == id)
      return true;
  }

  return false;
}

void ResetTrackingState(const bool clear_ritual_ids)
{
  s_menu_id_memory.last = 0;
  s_menu_id_memory.now = 0;

  for (MemoryElement* element : s_fm_memory)
  {
    element->last = 0;
    element->now = 0;
  }

  if (clear_ritual_ids)
    s_state.ritual_ids_sent.clear();
}

bool VerifyROM()
{
  std::array<u8, VERIFICATION_BYTES.size()> header_bytes = {};
  return CPU::SafeReadMemoryBytes(0x10384, header_bytes.data(), static_cast<u32>(header_bytes.size())) &&
         header_bytes == VERIFICATION_BYTES;
}

void UpdateMemory()
{
  for (MemoryElement* element : s_fm_memory)
    element->Update();
}

bool EnsureConnected()
{
  if (s_state.client && s_state.client->IsConnected())
    return true;

  if (s_state.frames_until_reconnect > 0)
  {
    s_state.frames_until_reconnect--;
    return false;
  }

  SocketMultiplexer* multiplexer = System::GetSocketMultiplexer();
  if (!multiplexer)
    return false;

  Error error;
  const std::optional<SocketAddress> address =
    SocketAddress::Parse(SocketAddress::Type::IPv4, SERVER_IP.data(), SERVER_PORT, &error);
  if (!address.has_value())
  {
    ERROR_LOG("Failed to parse socket address: {}", error.GetDescription());
    s_state.frames_until_reconnect = RECONNECT_INTERVAL_FRAMES;
    return false;
  }

  s_state.client = multiplexer->ConnectStreamSocket<ClientSocket>(address.value(), &error);
  if (!s_state.client)
  {
    if (!s_state.warned_about_connection)
    {
      WARNING_LOG("Failed to connect to {}:{}: {}", SERVER_IP, SERVER_PORT, error.GetDescription());
      s_state.warned_about_connection = true;
    }

    s_state.frames_until_reconnect = RECONNECT_INTERVAL_FRAMES;
    System::ReleaseSocketMultiplexer();
    return false;
  }

  s_state.frames_until_reconnect = RECONNECT_INTERVAL_FRAMES;
  return s_state.client->IsConnected();
}

void SendJsonMessage(const std::string_view message)
{
  if (!EnsureConnected())
    return;

  if (const size_t written = s_state.client->Write(message.data(), message.size()); written != message.size())
    WARNING_LOG("Short write sending FM Team Hundo payload ({} of {} bytes).", written, message.size());
}

void SendCard(const JsonType type, const u32 id)
{
  std::string_view type_name;
  switch (type)
  {
    case JsonType::Drop:
      type_name = "drop";
      break;

    case JsonType::Fuse:
      type_name = "fuse";
      break;

    case JsonType::Ritual:
      type_name = "ritual";
      break;
  }

  std::string message = fmt::format("{{\"type\":\"{}\",\"value\":{}", type_name, id);
  if (type == JsonType::Drop)
  {
    message.append(fmt::format(",\"last_rng\":{}", s_rng_memory.last));
    message.append(fmt::format(",\"now_rng\":{}", s_rng_memory.now));
  }

  message.append(fmt::format(",\"opp_id\":{}}}\n", s_opponent_id_memory.now));
  SendJsonMessage(message);
}

void SendStarchips()
{
  SendJsonMessage(fmt::format("{{\"type\":\"starchips\",\"value\":{}}}\n", s_starchip_memory.now));
}

} // namespace

ClientSocket::ClientSocket(SocketMultiplexer& multiplexer, SocketDescriptor descriptor)
  : BufferedStreamSocket(multiplexer, descriptor, 1, 16384)
{
}

ClientSocket::~ClientSocket() = default;

void ClientSocket::OnConnected()
{
  Error error;
  if (!SetNagleBuffering(false, &error))
    WARNING_LOG("Failed to disable Nagle buffering: {}", error.GetDescription());

  INFO_LOG("Connected to {}.", GetRemoteAddress().ToString());
  s_state.warned_about_connection = false;
}

void ClientSocket::OnDisconnected(const Error& error)
{
  WARNING_LOG("Disconnected from FM Team Hundo server: {}", error.GetDescription());
  s_state.client.reset();
  s_state.frames_until_reconnect = RECONNECT_INTERVAL_FRAMES;
}

void ClientSocket::OnRead()
{
  const std::span<const u8> buffer = AcquireReadBuffer();
  ReleaseReadBuffer(buffer.size());
}

void Reset()
{
  ResetTrackingState(true);
}

void Shutdown()
{
  ResetTrackingState(true);

  if (s_state.client)
  {
    s_state.client->Close();
    s_state.client.reset();
  }

  s_state.frames_until_reconnect = 0;
  s_state.warned_about_connection = false;
  System::ReleaseSocketMultiplexer();
}

void OnFrameDone()
{
  if (!VerifyROM()) {
    INFO_LOG("Not an FM ROM!");
    return;
  }

  // Maintain an open connection to the server
  EnsureConnected();

  s_menu_id_memory.Update();
  if (s_menu_id_memory.now <= 0x04)
    return;

  UpdateMemory();

  if (s_menu_id_memory.last <= 0x04)
  {
    for (MemoryElement* element : s_fm_memory)
      element->Initialize();
  }

  for (const MemoryElement& monster : s_player_monsters)
  {
    if (monster.now == 0 || !IsRitualMonster(monster.now) ||
        s_state.ritual_ids_sent.find(monster.now) != s_state.ritual_ids_sent.end())
    {
      continue;
    }

    SendCard(JsonType::Ritual, monster.now);
    s_state.ritual_ids_sent.insert(monster.now);
  }

  if (s_player_fusion_count.HasIncreased())
    SendCard(JsonType::Fuse, s_fuse_equip_ritual_memory.now);

  if (s_drop_memory.HasChanged())
    SendCard(JsonType::Drop, s_drop_memory.now);

  if (s_starchip_memory.HasChanged())
    SendStarchips();
}

} // namespace FMTeamHundo
