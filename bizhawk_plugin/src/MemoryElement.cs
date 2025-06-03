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

using BizHawk.Client.Common;

public class MemoryElement
{
    public int Last { get; private set; }
    public int Now { get; private set; }
    public int MemoryAddress { get; }
    public int MemoryLength { get; }
    private ApiContainer Api { get; }

    public MemoryElement(ApiContainer api, int memoryAddress, int memoryLength)
    {
        Last = 0;
        Now = 0;
        MemoryAddress = memoryAddress;
        MemoryLength = memoryLength;
        Api = api;
    }

    private void NewValue(int newValue)
    {
        Last = Now;
        Now = newValue;
    }

    public bool HasChanged()
    {
        return Last != Now;
    }

    public bool HasIncreased()
    {
        return Now > Last;
    }

    public void Update()
    {
        int newValue = ReadBizHawkBytes(Api, MemoryAddress, MemoryLength);
        NewValue(newValue);
    }

    public void Initialize()
    {
        Last = Now;
    }

    public static int ReadBizHawkBytes(ApiContainer api, int address, int length)
    {
        var bytesRead = api.Memory.ReadByteRange(address, length);
        int result = 0;
        for (int i = 0; i < length; i++)
        {
            result |= bytesRead[i] << (8 * i); // Little-endian
        }
        return result;
    }
}
