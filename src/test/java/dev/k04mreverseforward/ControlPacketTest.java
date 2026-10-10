package dev.k04mreverseforward;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlPacketTest {
    /**
     * Verifies that round trips every control type.
     *
     * @throws Exception if the delegated operation cannot complete successfully
     */
    @Test
    void roundTripsEveryControlType() throws Exception {
        for (ControlPacket.Type type : ControlPacket.Type.values()) {
            ControlPacket expected = new ControlPacket(type, UUID.randomUUID(), UUID.randomUUID(),
                    "my_route-1", 65535, 1);
            assertEquals(expected, ControlPacket.decode(expected.encode()));
        }
    }

    /**
     * Verifies that rejects truncated and trailing packets.
     */
    @Test
    void rejectsTruncatedAndTrailingPackets() {
        ControlPacket packet = new ControlPacket(ControlPacket.Type.INVITE, UUID.randomUUID(), UUID.randomUUID(),
                "route", 1234, 4321);
        byte[] encoded = packet.encode();
        assertThrows(IOException.class, () -> ControlPacket.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IOException.class, () -> ControlPacket.decode(trailing));
    }

    /**
     * Verifies that validates route names and ports.
     */
    @Test
    void validatesRouteNamesAndPorts() {
        assertThrows(IllegalArgumentException.class, () -> new ControlPacket(ControlPacket.Type.INVITE,
                UUID.randomUUID(), UUID.randomUUID(), "not valid", 1234, 4321));
        assertThrows(IllegalArgumentException.class, () -> new ControlPacket(ControlPacket.Type.INVITE,
                UUID.randomUUID(), UUID.randomUUID(), "valid", 0, 4321));
    }

    /**
     * Verifies that accept packet carries receiver selected target port.
     *
     * @throws Exception if the delegated operation cannot complete successfully
     */
    @Test
    void acceptPacketCarriesReceiverSelectedTargetPort() throws Exception {
        ControlPacket accepted = new ControlPacket(ControlPacket.Type.ACCEPT, UUID.randomUUID(), UUID.randomUUID(),
                "web", 25570, 9090);

        assertEquals(9090, ControlPacket.decode(accepted.encode()).targetPort());
    }
}
