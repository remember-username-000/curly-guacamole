import java.net.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class WebSocket {
    private static final String WEBSOCKET_MAGIC_STRING = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static void main (String[] args) throws IOException, NoSuchAlgorithmException {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("10.100.187.83", 80));
        System.out.println("Server initiated");

        try {
            Socket client = server.accept();
            System.out.println("Client detected");

            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            Scanner scanner = new Scanner(in, "UTF-8");

            try {
                scanner.useDelimiter("\\r\\n\\r\\n");
                String data = scanner.next();
                System.out.println("\nNew HTTP request:\n" + data + "\n");

                Matcher detectGetRequest = Pattern.compile("^GET").matcher(data);
                if (detectGetRequest.find()) {
                    System.out.println("GET request detected");
                    
                    Matcher detectWebSocketKey = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(data);
                    if (detectWebSocketKey.find()) {
                        String key = detectWebSocketKey.group(1);
                        System.out.println("Sec-WebSocket-Key found: " + key);
                        
                        String response = (
                            "HTTP/1.1 101 Switching Protocols\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: "
                                + Base64.getEncoder()
                                    .encodeToString(
                                        MessageDigest.getInstance("SHA-1")
                                            .digest(
                                                (key + WEBSOCKET_MAGIC_STRING).getBytes("UTF-8")
                                            )
                                    )
                                + "\r\n"
                            + "\r\n"
                        )/*.getBytes("UTF-8")*/;
                        System.out.println("\nSending HTTP response:\n" + response + "\n");

                        byte[] responseBytes = response.getBytes("UTF-8");
                        out.write(responseBytes, 0, responseBytes.length);
                    }
                }
                
                while (true) {
                    String decoded = readClientDataFrame(in);
                    System.out.println(decoded);
                    System.out.println();

                    if(decoded.equals("WOULD_YOU_KINDLY")) {
                        break;
                    }
                    
                    out.write(generateResponseByteArray("(echo) " + decoded));
                }

            } finally {
                scanner.close();
            }        
        } finally {
            server.close();
            System.out.println("Server closed");
        }
    }

    private static String readClientDataFrame (InputStream in) throws IOException {
        byte a = (byte) in.read();
        System.out.println("New message:");
        boolean FIN = ((a & 0x80) != 0);
        int OPCODE = (int)(a & 0x0F);
        System.out.println("FIN: " + FIN + "; OPCODE: " + OPCODE);
        if(OPCODE == 8) {
            return "WOULD_YOU_KINDLY";
        }
    
        byte b = (byte) in.read();
        boolean MASK = ((b & 0x80) != 0);
        int payloadLength = (int)(b & 0x7F);
        if (payloadLength == 126) {
            byte[] lenBufferArray = new byte[2];
            in.read(lenBufferArray, 0, 2);
            payloadLength = ((int)(ByteBuffer.wrap(lenBufferArray).getShort()) & 0xFFFF);
        } else if (payloadLength == 127) {
            //don't want to deal with long messages; will write in an error throw later
            System.out.println("message too long");
            return "CLIENTERROR_MESSAGETOOLONG";
            /*byte[] lenBufferArray = new byte[8];
            in.read(lenBufferArray, 0, 8);
            payloadLength = (long)(ByteBuffer.wrap(lenBufferArray).getLong());*/
        }
        System.out.println("MASK: " + MASK + "; payloadLength: " + payloadLength);

        byte[] maskingKeyByteArray = new byte[4];
        in.read(maskingKeyByteArray, 0, 4);

        byte[] payloadByteArray = new byte[payloadLength];
        in.read(payloadByteArray, 0, payloadLength);

        byte[] decodedPayloadByteArray = new byte[payloadLength];
        for (int i = 0; i < payloadLength; i++) {
            decodedPayloadByteArray[i] = (byte)(payloadByteArray[i] ^ maskingKeyByteArray[i % 4]);
        }

        String decoded = new String(decodedPayloadByteArray, "UTF-8");
        return decoded;
    }

    private static byte[] generateResponseByteArray (String payload) throws IOException {
        //ArrayList<Byte> byteArrayList = new ArrayList<Byte>();
        
        byte a = ((byte) 0b10000001);

        byte b = ((byte) 0b00000000);
        byte[] payloadLength = new byte[0];

        if (payload.length() <= 125) {
            b = ((byte) ((byte)payload.length() & 0b01111111)); //no masking, therefore make sure first bit of second byte is 0
        } else if (payload.length() <= 32767) { //technically can go up to 65535 but i don't want to deal with long messages
            b = ((byte) 0b01111110); // = MASK = 0; 126

            payloadLength = ByteBuffer.allocate(2)
                .putShort((short) payload.length())
                .array();
        } else {
            //don't want to deal with long messages; will write in an error throw later
            System.out.println("message too long");
            return new byte[0];
        }

        byte[] payloadBytes = payload.getBytes("UTF-8");

        byte[] responseBytes = new byte[2 + payloadLength.length + payloadBytes.length];
        responseBytes[0] = a;
        responseBytes[1] = b;
        System.arraycopy(payloadLength, 0, responseBytes, 2, payloadLength.length);
        System.arraycopy(payloadBytes, 0, responseBytes, (2 + payloadLength.length), payloadBytes.length);

        /*
        System.out.println("Response generated:\n");
        for (int i = 0; i < responseBytes.length; i++) {
            System.out.print(responseBytes[i] + " ");
        }
        System.out.println();
        */

        //System.out.println(byteArrayList);
        return responseBytes;
    }
}