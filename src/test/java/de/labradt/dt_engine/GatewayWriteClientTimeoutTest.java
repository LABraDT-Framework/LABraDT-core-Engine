package de.labradt.dt_engine;

import de.labradt.dt_engine.engine.GatewayWriteClient;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The write back PATCH to the Gateway has connect and read timeouts (T44), so a Gateway
 * that accepts the connection but never answers cannot block the engine.
 */
public class GatewayWriteClientTimeoutTest {

    @Test
    public void defaultsAreThreeAndTenSeconds() throws Exception {
        GatewayWriteClient client = new GatewayWriteClient();
        assertEquals(3000, get(client, "connectTimeoutMs"));
        assertEquals(10000, get(client, "readTimeoutMs"));
    }

    @Test
    public void applicationPropertiesDeclareTheTimeouts() throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        assertEquals("3000", p.getProperty("gateway.write.connect-timeout-ms"));
        assertEquals("10000", p.getProperty("gateway.write.read-timeout-ms"));
    }

    @Test(timeout = 10_000)
    public void readTimeoutStopsAGatewayThatNeverAnswers() throws Exception {
        try (ServerSocket silentGateway = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    Socket s = silentGateway.accept();   // accept, read nothing back, never answer
                    Thread.sleep(30_000);
                    s.close();
                } catch (Exception ignored) {
                    // socket closed at the end of the test
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            GatewayWriteClient client = new GatewayWriteClient();
            set(client, "baseWriteUrl", "http://127.0.0.1:" + silentGateway.getLocalPort());
            set(client, "machineId", "cnc-line1");
            set(client, "readTimeoutMs", 300);
            Method apply = GatewayWriteClient.class.getDeclaredMethod("applyTimeouts");
            apply.setAccessible(true);
            apply.invoke(client);

            long start = System.nanoTime();
            client.writeValue("CPS_SM", "Temperature", "70.0");  // logs "Write back failed", no exception
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue("write back should give up after the 300 ms read timeout, took " + elapsedMs + " ms",
                elapsedMs >= 250 && elapsedMs < 5_000);
        }
    }

    private static Object get(Object target, String name) throws Exception {
        Field f = GatewayWriteClient.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = GatewayWriteClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
