package prerna.websocket;

/**
 * Interface for any class that tails/streams file changes and broadcasts
 * updates to WebSocket clients.
 *
 * Implementations are expected to:
 * <ul>
 *   <li>Block the calling thread in {@link #start()} until stopped</li>
 *   <li>Use {@link SocketSessionHandlerFactory} to broadcast to WS clients</li>
 *   <li>Clean up resources when {@link #stop()} is called</li>
 * </ul>
 *
 * Register implementations with {@link StreamerRegistry} so they can be
 * resolved by type from WebSocket messages.
 */
public interface FileStreamer {

	/** Begin streaming. Blocks the calling thread until {@link #stop()} is called. */
	void start();

	/** Signal the streamer to stop after the current cycle. */
	void stop();

	/** Whether the streamer is currently running. */
	boolean isRunning();
}
