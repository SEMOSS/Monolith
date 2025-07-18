package prerna.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * A simple client to interact with the MCP SSE endpoint.
 */
public class MCPClient {

    private static final String BASE_URL = "http://localhost:9080/Monolith/api/ext/mcp/62cb9f38-25d4-4209-81ae-54178ad7ae66/comms";
    private static final String AUTH_HEADER = "Bearer59273b71-7c59-4225-848f-796f074d1cd6:02e8f681-dcaf-452e-8266-ad91b9244c91";
    private static int idCounter = 0;

    public static void main(String[] args) {
    		command();
    		/*
        if (args.length == 0) {
            printUsage();
            System.out.println("No command provided, sending 'initialize' by default as an example.");
            sendRequest(buildInitializeMessage());
            return;
        }

        String command = args[0];
        */

    }
    
    private static void command()
    {
    		try {
				String command = null;
				System.err.println("Enter command");
				BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
				while((command = br.readLine()) != null)
					runCommand(command);
				System.err.println("Enter command");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    	
    }
    
    private static void runCommand(String command)
    {
        switch (command) {
        case "initialize":
            sendRequest(buildInitializeMessage());
            break;
        case "list_tools":
            sendRequest(buildListToolsMessage());
            break;
        case "list_resources":
            sendRequest(buildListResourcesMessage());
            break;
            
        /*case "call_tool":
            if (args.length < 3) {
                System.err.println("Error: 'call_tool' requires a tool name and a JSON string of arguments.");
                printUsage();
                return;
            }
            String toolName = args[1];
            String toolArgs = args[2];
            sendRequest(buildCallToolMessage(toolName, toolArgs));
            break;
            */
        default:
            System.err.println("Error: Unknown command '" + command + "'");
            printUsage();
            break;
    }

    }
    

    private static void printUsage() {
        System.err.println("Usage: java prerna.mcp.MCPClient <command> [options]");
        System.err.println("Commands:");
        System.err.println("  initialize                      - Initializes the connection");
        System.err.println("  list_tools                      - Lists available tools");
        System.err.println("  list_resources                  - Lists available resources");
        System.err.println("  call_tool <name> '<args_json>'  - Calls a tool with the given name and arguments");
        System.err.println("Example:");
        System.err.println("  java prerna.mcp.MCPClient call_tool get_stock_price '{\"symbol\":\"GOOG\"}'");
        System.err.println();
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

    private static void sendRequest(String jsonMessage) {
        HttpURLConnection connection = null;
        try {
            // 1. Setup Connection
            URL url = new URL(BASE_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", AUTH_HEADER);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000); // 5 seconds
            connection.setReadTimeout(30000); // 30 seconds

            // 2. Send Request
            System.out.println(">> Sending to server:");
            System.out.println(jsonMessage);
            try (OutputStream os = connection.getOutputStream()) {
                // add a newline because the server reads line by line
                byte[] input = (jsonMessage + "").getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            // 3. Read Response
            System.out.println("<< Receiving from server:");
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "utf-8"))) {
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    System.out.println(responseLine);
                }
            }

        } catch (Exception e) {
            System.err.println("An error occurred during the request:");
            e.printStackTrace();
            // Try to read error stream if available
            if (connection != null) {
                try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(connection.getErrorStream(), "utf-8"))) {
                    System.err.println("<< Error Stream from server:");
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        System.err.println(responseLine);
                    }
                } catch (Exception ex) {
                    // ignore if we can't read the error stream
                }
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
