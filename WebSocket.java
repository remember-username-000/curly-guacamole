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
import java.util.List;
//import java.util.Iterator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class WebSocket {
    private static final String WEBSOCKET_MAGIC_STRING = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    
    //private static ArrayList<ClientHandlerThread> liveThreadList = new ArrayList<ClientHandlerThread>();
    private static List<ClientHandlerThread> liveThreadList = Collections.synchronizedList(new ArrayList<ClientHandlerThread>());
    private static ServerSocket server;

    private static volatile boolean clientInitiatedShutDown = false;

    private static String serverHostname = "127.0.0.1";
    private static int serverPort = 80;
    private static boolean doAutoClose = false;
    private static boolean allowClientShutdown = false;

    public static void main (String[] args) throws IOException {
        parseArgs(args);

        server = new ServerSocket();
        try {
            server.bind(new InetSocketAddress(serverHostname, serverPort));
        } catch (Exception e) {
            System.out.println("Error starting server with provided args: " + e);
            return;
        }

        System.out.println("\nServer initiated on " + server.getLocalSocketAddress());

        ConnectHandlerThread  connectHandler = new ConnectHandlerThread(server);
        connectHandler.start();

        while (true) {
            System.out.println("    beep");
            try {
                Thread.sleep(5000);
                if ((liveThreadList.size() == 0) && doAutoClose) {
                    break;
                } else if (clientInitiatedShutDown) {
                    break;
                }
            } catch (InterruptedException e) {
                System.out.println("big shit");
                break;
            }
        }
        System.out.println("Closing server: no connections or user manual shutdown");
        closeServer();
        try {
            connectHandler.join();
        } catch (InterruptedException e) {
            System.out.println("Connection handler interrupted during join()");
        }
        System.out.println("Closing successfully completed, exiting JVM");
    }

    private static String readClientDataFrame (InputStream in) throws IOException {
        byte a = (byte) in.read();
        System.out.println("New message:");
        boolean FIN = ((a & 0x80) != 0);
        int OPCODE = (int)(a & 0x0F);
        System.out.println("FIN: " + FIN + "; OPCODE: " + OPCODE);
        if(OPCODE == 8) {
            return "CLIENT_CLOSED_CONNECTION";
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
        } else if (payload.length() < 32767) { //technically can go up to 65535 but i don't want to deal with long messages
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

    private static class ClientHandlerThread extends Thread {
        private Socket client;
        private InputStream in;
        private OutputStream out;
        //public boolean isLive;
        
        ClientHandlerThread(Socket clientSocket) {
            super();
            this.client = clientSocket;
            try {
                this.in = client.getInputStream();
                this.out = client.getOutputStream();
                //this.isLive = true;
            } catch (IOException e) {
                System.out.println("Error getting stream from Socket: " + e);
                //this.isLive = false;
            }
        }

        public void run () {
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
                        );//.getBytes("UTF-8");
                        System.out.println("\nSending HTTP response:\n" + response + "\n");

                        byte[] responseBytes = response.getBytes("UTF-8");
                        out.write(responseBytes, 0, responseBytes.length);
                    }
                }
                
                while (true) {
                    String decoded = readClientDataFrame(in);
                    System.out.println(decoded);
                    System.out.println();

                    if (decoded.equals("CLIENT_CLOSED_CONNECTION")) {
                        break;
                    } else if (decoded.equals("WOULD_YOU_KINDLY")) {
                        //new Thread(() -> {closeServer();}).start();
                        if (allowClientShutdown) {
                            clientInitiatedShutDown = true;
                        }
                    }
                    
                    //out.write(generateResponseByteArray("(echo) " + decoded));

                    byte[] echo = generateResponseByteArray("(echo) " + decoded);
                    synchronized (liveThreadList) {
                        for (int i = liveThreadList.size() - 1; i >= 0; i--) {
                            liveThreadList.get(i).write(echo);
                        }
                    }
                }

            } catch (IOException | NoSuchAlgorithmException e) {
                System.out.println("Error in thread: " + e);
            } finally {
                liveThreadList.remove(Thread.currentThread());
                scanner.close();
                System.out.println("Live threads: " + liveThreadList);
                //isLive = false;
            }
            System.out.println("Thread ended");
        }

        public void close () {
            try {
                in.close();
                out.close();
            } catch (IOException e) {
                System.out.println("Error closing thread");
            } finally {
                liveThreadList.remove(Thread.currentThread());
            }
        }

        public void write (byte[] byteArray) throws IOException {
            out.write(byteArray, 0, byteArray.length);
        }
    }

    private static void closeServer() {
        synchronized (liveThreadList) {
            for (int i = liveThreadList.size() - 1; i >= 0; i--) {
                ClientHandlerThread t = liveThreadList.get(i);
                /*try {
                    t.close();
                    //t.join();
                    System.out.println("Manually closed thread " + t);
                } catch (InterruptedException e) {
                    System.out.println("Thread interrupted while closing: " + e);
                }*/
                t.close();
                System.out.println("Manually closed thread " + t);
            }
        }
        try {
            server.close();
            System.out.println("Server closed");
        } catch (IOException e) {
            System.out.println("Error closing server: " + e);
            System.out.println("Force exiting JVM");
            System.exit(1);
        }
    }

    private static class ConnectHandlerThread extends Thread {
        private ServerSocket server;

        ConnectHandlerThread (ServerSocket s) {
            super();
            this.server = s;
        }
        public void run () {
            while (true) {
                try {
                    Socket client = server.accept();
                    System.out.println("Client detected");

                    ClientHandlerThread thread = new ClientHandlerThread(client);
                    thread.start();
                    liveThreadList.add(thread);
                    System.out.println("Live threads: " + liveThreadList);
                    System.out.println("Thread started");

                } catch (SocketException se) {
                    System.out.println("Socket closed during ServerSocket.accept()");
                    break;
                } catch (IOException ie) {
                    System.out.println("Error accepting client: " + ie);
                }
            }
            System.out.println("Connection thread closed");
        }
    }

    private static void parseArgs (String[] args) {
        for (int i = 0; i < args.length; i++) {
            String s = args[i];
            try {
                if (s.equals("-h") || s.equals("--help")) {
                    displayHelp();
                } else if (s.equals("-a") || s.equals("--host-address")) {
                    serverHostname = args[i+1];
                    i++;
                    System.out.println("Attempting to host server on " + serverHostname);
                } else if (s.equals("-p") || s.equals("--port")) {
                    serverPort = Integer.parseInt(args[i+1]);
                    i++;
                    System.out.println("Attempting to use port " + serverPort);
                } else if (s.equals("-c") || s.equals("--auto-close")) {
                    doAutoClose = true;
                    System.out.println("Auto-close enabled");
                } else if (s.equals("-w") || s.equals("--allow-client-shutdown")) {
                    allowClientShutdown = true;
                    System.out.println("Client shutdown enabled");
                } else if (s.charAt(0) == '-') {
                    System.out.println("Unrecognized flag\nRun with arg -h or --help to get help");
                    System.exit(0);
                }
            } catch (Exception e) {
                System.out.println("Exception occurred while parsing arguments:" + e + "\nRun with arg -h or --help to get help");
                System.exit(0);
            }
        }
    }

    public static void displayHelp () {
        System.out.println(
            "\n"
            +     "\nCreates a WebSocket server that broadcasts messages to all clients"
            +   "\n\nCommandline options:"
            +   "\n\n    Options can be entered in any order"
            +     "\n    Some options require arguments, which must immediately follow the option"
            +     "\n    All entries should be separated by a single space"
            +     "\n    Syntax:"
            +     "\n        plain text    Enter this entry exactly as shown"
            +     "\n        <...>         Should be replaced with an appropriate value"
            +     "\n        (...|...)     Pick one of the listed entries"
            + "\n\n\n    --help"
            +     "\n        (-h|--help)"
            +   "\n\n        Displays the text that you're reading right now"
            + "\n\n\n    --host-address"
            +     "\n        (-a|--host-address) <IP address>"
            +   "\n\n        Specifies the local IP address that will be hosting the server"
            + "\n\n\n    --port"
            +     "\n        (-p|--port) <port number>"
            +   "\n\n        Specifies the port that the server will listen on"
            + "\n\n\n    --auto-close"
            +     "\n        (-c|--auto-close)"
            +   "\n\n        Allows the server to close if there are no live connections"
            + "\n\n\n    --allow-client-shutdown"
            +     "\n        (-w|--allow-client-shutdown)"
            +   "\n\n        Allows clients to shut down the server using shutdown string"
            +"\n"
        );
        System.exit(0);
    }
}