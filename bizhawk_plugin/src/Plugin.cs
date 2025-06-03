// Copyright (C) 2025 sg4e
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

namespace YGOFMPlugin.DataSource;

using System.Net.Sockets;
using System.Text;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;

using BizHawk.Client.Common;
using BizHawk.Client.EmuHawk;
using System.Collections.Generic;

[ExternalTool("FM Team Hundo")]
public sealed class FMTeamHundoForm : ToolFormBase, IExternalToolForm
{
    public ApiContainer? _maybeAPIContainer { get; set; }

    public const string ServerIp = "127.0.0.1";
    public const int ServerPort = 8080;

    private readonly Label _lblLevel = new() { AutoSize = true };

    private ApiContainer APIs
        => _maybeAPIContainer!;

    protected override string WindowTitleStatic
        => "FM Team Hundo Bizhawk Plugin";

    private readonly byte[] verificationBytes = [
        0x42, 0x41, 0x53, 0x4C, 0x55, 0x53, 0x2D, 0x30, 0x31, 0x34, 0x31, 0x31,
        0x2D, 0x59, 0x55, 0x47, 0x49, 0x4F, 0x48, 0x00
    ];

    private readonly int[] ritualMonsterIds = [
        356,
        357,
        360,
        362,
        364,
        365,
        374,
        380,
        701,
        702,
        703,
        704,
        705,
        706,
        708,
        710,
        715,
        716,
        718,
        719,
        720
    ];

    private readonly TcpClient client;
    private readonly HashSet<int> idsSent = [];  // only used for Ritual Summons for now

    private MemoryElement fuseEquipRitualMemory, dropMemory, starchipMemory, rngMemory, menuIdMemory, playerFusionCount;
    private MemoryElement monster1, monster2, monster3, monster4, monster5;
    private MemoryElement[] fmMemory, playerMonsters;


    public FMTeamHundoForm()
    {
        ClientSize = new Size(480, 320);
        SuspendLayout();
        Controls.Add(_lblLevel);
        ResumeLayout(performLayout: false);
        PerformLayout();

        client = new TcpClient(ServerIp, ServerPort);
    }

    private bool IsRitualMonster(int id)
    {
        for (int i = 0; i < ritualMonsterIds.Length; i++)
        {
            if (ritualMonsterIds[i] == id) return true;
        }
        return false;
    }

    public override void Restart()
    {
        fuseEquipRitualMemory = new MemoryElement(APIs, 0xEA118, 2);
        dropMemory = new MemoryElement(APIs, 0x1D56A8, 2);
        starchipMemory = new MemoryElement(APIs, 0x1D07E0, 4);
        rngMemory = new MemoryElement(APIs, 0xFE6F8, 4);
        playerFusionCount = new MemoryElement(APIs, 0xe9ff8, 1);
        menuIdMemory = new MemoryElement(APIs, 0x184594, 1);
        monster1 = new MemoryElement(APIs, 0x1a7b70, 2);
        monster2 = new MemoryElement(APIs, 0x1a7b8c, 2);
        monster3 = new MemoryElement(APIs, 0x1a7ba8, 2);
        monster4 = new MemoryElement(APIs, 0x1a7bc4, 2);
        monster5 = new MemoryElement(APIs, 0x1a7be0, 2);
        fmMemory = [
            fuseEquipRitualMemory,
            dropMemory,
            starchipMemory,
            rngMemory,
            playerFusionCount,
            // menuIdMemory is excluded
            monster1,
            monster2,
            monster3,
            monster4,
            monster5
        ];
        playerMonsters = [
            monster1,
            monster2,
            monster3,
            monster4,
            monster5
        ];
    }

    private void UpdateMemory()
    {
        foreach (var mem in fmMemory)
        {
            mem.Update();
        }
    }

    private bool VerifyROM()
    {
        var headerBytes = APIs.Memory.ReadByteRange(0x10384, verificationBytes.Length);
        return headerBytes.SequenceEqual(verificationBytes);
    }

    private void SendCard(JsonType type, int id)
    {
        StringBuilder s = new();
        s.Append("{\"type\":\"");
        switch (type)
        {
            case JsonType.Drop: s.Append("drop\","); break;
            case JsonType.Fuse: s.Append("fuse\","); break;
            case JsonType.Ritual: s.Append("ritual\","); break;
        }
        s.Append("\"value\":");
        s.Append(id);
        if (type == JsonType.Drop)
        {
            s.Append(",\"last_rng\":");
            s.Append(rngMemory.Last);
            s.Append(",\"now_rng\":");
            s.Append(rngMemory.Now);
        }
        s.Append("}\n");
        SendJsonMessage(s.ToString());
    }

    private void SendStarchips()
    {
        StringBuilder s = new(37);  // 999,999 starchips
        s.Append("{\"type\":\"starchips\",\"value\":");
        s.Append(starchipMemory.Now);
        s.Append("}\n");
        SendJsonMessage(s.ToString());
    }

    private void SendJsonMessage(string message)
    {
        byte[] messageBytes = Encoding.UTF8.GetBytes(message);
        var stream = client.GetStream();
        stream.Write(messageBytes, 0, messageBytes.Length);
    }

    protected override void UpdateAfter()
    {
        // Important RAM values:
        // Drops: 1D56A8
        // Card after a fusion/equip/ritual: EA118
        // Is it the player's turn? If e9ff1 == ea011, it's the player's turn (player turn count vs. opp. turn count. Opp. turn is always one ahead from start of duel)
        // when e9ff8 (player fusion count, single byte) increments, EA118 is already set to what was fused. Use this to track fusions
        // Tracking rituals sucks tho. RA has a convoluted sequence of checks for them
        // We might need them tho. If the AI equips a ritual-summonable card on their turn, it will still be in RAM address during the player turn
        // After giving it some thought, I think the best way to track rituals is checking whether a ritual-summonable card is in any of the player's 5 monster slots
        // The card id RAM locations for those 5 slots are as follows. These ids are player-sided only:
        // 1a7b70
        // 1a7b8c
        // 1a7ba8
        // 1a7bc4
        // 1a7be0
        // Implementation per frame:
        // Check every player monster. If any is a ritual monster, send its id, then save the id in memory here to prevent resending till emu is reloaded
        // Check the fusion count RAM. If it's greater than last frame, send id for fusion RAM. Regardless, set the current value as reference
        // Check the drop. If it's different from last frame, send its id
        // Check the starchip count. If it's different from last frame, send it.
        // Notes for players:
        // 1. Once you send a ritual summon, it will not be re-sent until you relaunch the emulator
        // 2. If you receive the same drop twice, the 2nd drop with not be granted. This is problematic for Blue Eyes farm and will require manual intervention if 2 Blue Eyes are won in a row
        if (VerifyROM())
        {
            _lblLevel.Text = "Verified ROM";
            menuIdMemory.Update();
            if (menuIdMemory.Now > 0x04)
            {
                UpdateMemory();
                if (menuIdMemory.Last <= 0x04)
                {
                    // player just loaded game
                    // mark all memory values (loaded from SRAM) as "old"
                    foreach (var mem in fmMemory)
                    {
                        mem.Initialize();
                    }
                }
                foreach (var monsterMem in playerMonsters)
                {
                    int id = monsterMem.Now;
                    if (id != 0 && IsRitualMonster(id) && !idsSent.Contains(id))
                    {
                        SendCard(JsonType.Ritual, id);
                        idsSent.Add(id);
                    }
                }
                if (playerFusionCount.HasIncreased())
                {
                    SendCard(JsonType.Fuse, fuseEquipRitualMemory.Now);
                }
                if (dropMemory.HasChanged())
                {
                    SendCard(JsonType.Drop, dropMemory.Now);
                }
                if (starchipMemory.HasChanged())
                {
                    SendStarchips();
                }
            }
        }
        else
        {
            _lblLevel.Text = "Not an FM ROM";
        }
    }

    protected override void FastUpdateAfter()
    {
        _lblLevel.Text = "Turbo is not supported. Please play on normal speed to participate in Team Hundo";
    }
}
