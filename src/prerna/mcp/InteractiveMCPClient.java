package prerna.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

/**
 * An interactive, stateful client to interact with the MCP SSE endpoint.
 * Maintains a persistent connection to allow for a conversational session with easy-to-use commands.
 */
public class InteractiveMCPClient {

    private static final String BASE_URL = "http://localhost:9080/Monolith/api/ext/mcp/62cb9f38-25d4-4209-81ae-54178ad7ae66/interactive-comms";
    private static final String AUTH_HEADER = "Bearer59273b71-7c59-4225-848f-796f074d1cd6:02e8f681-dcaf-452e-8266-ad91b9244c91";
    private static int idCounter = 0;
    public static  HttpURLConnection connection = null; // = null;

    public static void main(String[] args) {
        try {
            URL url = new URL(BASE_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", AUTH_HEADER);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);

            OutputStream os = connection.getOutputStream();

            // Listener thread to print messages from the server
            Thread listenerThread = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            System.out.println("\n<< " + line.substring(5).trim());
                        } else if (!line.trim().isEmpty()){
                            System.out.println("\n<< " + line);
                        }
                        System.out.print(">> "); // Reprint prompt after server message
                    }
                } catch (IOException e) {
                    System.out.println("\n<<< Server connection closed. >>>");
                }
            });
            listenerThread.setDaemon(true);
            listenerThread.start();

            // Send initial initialize message
            String initMessage = buildInitializeMessage();
            os.write((initMessage + "\n").getBytes("utf-8"));
            os.flush();

            printUsage();

            // Main loop to read user commands
            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.print(">> ");
                    String userInput = scanner.nextLine();
                    if (userInput == null || "exit".equalsIgnoreCase(userInput.trim())) {
                        break;
                    }
                    
                    String jsonToSend = parseCommand(userInput);
                    if (jsonToSend != null) {
                        os.write((jsonToSend + "\n").getBytes("utf-8"));
                        os.flush();
                    } else {
                        printUsage();
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("An error occurred: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            System.out.println("Client shutting down.");
        }
    }

    private static String parseCommand(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        // If the user enters raw JSON, just send it.
        if(input.trim().startsWith("{")) {
            return input;
        }

        String[] parts = input.trim().split("\\s+", 3);
        String command = parts[0].toLowerCase();

        switch (command) {
            case "initialize":
                return buildInitializeMessage();
            case "list_tools":
                return buildListToolsMessage();
            case "list_resources":
                return buildListResourcesMessage();
            case "call_tool":
                if (parts.length < 3) {
                    System.err.println("Error: 'call_tool' requires a tool name and a JSON string of arguments.");
                    return null;
                }
                return buildCallToolMessage(parts[1], parts[2]);
            case "help":
                return null; // Will trigger printUsage()
            default:
                System.err.println("Error: Unknown command '" + command + "'");
                return null;
        }
    }

    private static void printUsage() {
        System.out.println("\n<<< Interactive MCP Client >>>");
        System.out.println("Enter a command or raw JSON. Type 'exit' to quit.");
        System.out.println("Commands:");
        System.out.println("  initialize");
        System.out.println("  list_tools");
        System.out.println("  list_resources");
        System.out.println("  call_tool <name> <args_json>");
        System.out.println("  help");
        System.out.println("Example: call_tool get_stock_price {\"symbol\":\"GOOG\"}");
    }

    private static String buildInitializeMessage() {
        return String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"initialize\",\"params\":{}}", idCounter++);
    }

    private static String buildListToolsMessage() {
        return String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"tools/list\",\"params\":{}}", idCounter++);
    }

    private static String buildListResourcesMessage() {
        return String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"resources/list\",\"params\":{}}", idCounter++);
    }

    private static String buildCallToolMessage(String toolName, String arguments) {
        return String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"tools/call\",\"params\":{\"name\":\"%s\",\"arguments\":%s}}", idCounter++, toolName, arguments);
    }
}