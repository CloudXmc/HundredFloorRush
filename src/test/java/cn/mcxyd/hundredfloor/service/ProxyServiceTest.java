package cn.mcxyd.hundredfloor.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProxyServiceTest {

    @Test
    void createsBungeeConnectPayload() throws Exception {
        byte[] payload = ProxyService.connectPayload("lobby");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            assertEquals("Connect", input.readUTF());
            assertEquals("lobby", input.readUTF());
        }
    }
}
