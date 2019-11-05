package tgm.ffuchs.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

public class Server extends Thread{
    private Integer port = 5050;
    private String host = "localhost";
    private final Integer backlog = 5;
    private ServerSocket serverSocket = null;

    private boolean listening = false;
    private SimpleChat server;

    private ConcurrentHashMap<ClientWorker, String> workerList = new ConcurrentHashMap<>();
    private ExecutorService executorService = Executors.newCachedThreadPool();


    /**
     * Initializes host, port and callback for UserInterface interactions.
     *
     * @param host   String representation of hostname, on which the Server should listen
     * @param port   Integer for the listening port
     * @param server UserInterface callback reference for user interactions
     */
    public Server(String host, Integer port) {
        if (host != null) this.host = host;
        if (port != null) this.port = port;
        this.server = server;
    }

    /**
     * Initiating the ServerSocket with already defined Parameters and starts accepting incoming
     * requests. If client connects to the ServerSocket a new ClientWorker will be created and passed
     * to the ExecutorService for immediate concurrent action.
     */
    public void run() {
        int i = 1;
        try {
            serverSocket = new ServerSocket(port);
            listening = true;
            while (listening) {
                Socket clientSocket = this.serverSocket.accept();
                ClientWorker worker = new ClientWorker(clientSocket, this);
                this.workerList.put(worker, "Habschi #"+i);
                this.executorService.submit(worker);
                System.out.println( this.workerList.toString() );
                System.out.println( "Worker name: "+this.workerList.get(worker) );
                i++;
            }
        } catch (IOException e) {
        }
    }

    /**
     * Callback method for client worker to inform Server of new message arrival
     *
     * @param plainMessage MessageText sent to Server without Client information
     * @param sender       {@link ClientWorker} which received the message
     */
    public void received(String plainMessage, ClientWorker sender) {
        String[] words = plainMessage.trim( ).split( "\\s+" );
        /*
        if (plainMessage.substring( 0, 1 ).equals( "!" )) {
            MessageProtocol.Commands c = MessageProtocol.getCommand( words[0] );
            switch (c) {
                case EXIT:
                    this.removeClient(sender);
                    break;
                case CHATNAME:
                    this.setName(words[1], sender);
                    break;
                case PRIVATE:
                    System.out.println(words[1] );
                    break;
                default:
            }
        } else {
            this.server.incomingMessage(MessageProtocol.textMessage(plainMessage,this.workerList.get(sender)));
            this.send(MessageProtocol.textMessage(plainMessage,this.workerList.get(sender)));
        }

         */
    }

    /**
     * Sending messages to clients through communication framework
     *
     * @param message MessageText with sender ChatName
     */
    public void send(String message) {
        if (!this.workerList.isEmpty()) {
            for (ClientWorker key:workerList.keySet()) {
                key.send(message);
            }
        }
    }

    /**
     * Sending message to one client through communication framework
     *
     * @param message  MessageText with sender ChatName
     * @param receiver ChatName of receiving Client
     */
    public void send(String message, Object receiver) {
        if (!this.workerList.isEmpty()) {
            if (receiver instanceof String) {
                for (ClientWorker key : workerList.keySet( )) {
                    if (this.workerList.get( key ).equals( receiver )) { key.send( message ); }
                }
            } else if (receiver instanceof ClientWorker) {
                ((ClientWorker) receiver).send( message );
            }
        }
    }

    /**
     * ClientWorker has the possibility to change the ChatName. This method asks the UI
     * to rename the Client and stores the returned Name in the ClientWorker-Collection
     *
     * @param chatName new Name of Client
     * @param worker   ClientWorker Thread which was initiating the renaming
     */
    void setName(String chatName, ClientWorker worker) {
        String newName = this.server.renameClient(this.workerList.get(worker), chatName);
        this.workerList.replace(worker, newName);
        this.send("Your Chatname is: "+ this.workerList.get(worker), worker);
    }

    /**
     * Remove only this worker from the list,
     * shutdown the ClientWorker and also inform GUI about removal.
     *
     * @param worker ClientWorker which should be removed
     */
    void removeClient(ClientWorker worker) {
        this.server.removeClient(this.workerList.get(worker));
        this.workerList.remove(worker);
        this.listening = false;
        worker.shutdown();
    }

    /**
     * Gets the ClientWorker of the given chatName and calls the private Method {@link #removeClient(String)}
     * This method will remove the worker from the list shutdown the ClientWorker and also inform GUI about removal.
     *
     * @param chatName Client name which should be removed
     */
    public void removeClient(String chatName) {
        for (ClientWorker key : this.workerList.keySet()) {
            if (this.workerList.get(key).equals(chatName)) {
                this.removeClient(key);
                break;
            }
        }
    }

    /**
     * Clean shutdown of all connected Clients.<br>
     * ExecutorService will stop accepting new Thread inits.
     * After notifying all clients, ServerSocket will be closed and ExecutorService will try to shutdown all
     * active ClientWorker Threads.
     */
    public void shutdown() {
        this.send( "Oh, looks like you were removed from the Server!\nBut have a nice day anyway ^^!" );
        this.send( "!EXIT" );
        this.executorService.shutdown();
        this.listening = false;
        try {
            this.serverSocket.close();
        } catch (IOException e) {
            SimpleChat.serverLogger.log(INFO, "Fehler beim Schließen des Sockets");
        }
        this.executorService.shutdownNow();

    }
}

/**
 * Thread for client socket connection.<br>
 * Every client has to be handled by an own Thread.
 */
class ClientWorker implements Runnable {
    private Socket client;
    private PrintWriter out;
    private BufferedReader in;

    private SimpleChatServer callback;
    private boolean listening = true;

    /**
     * Init of ClientWorker-Thread for socket intercommunication
     *
     * @param client   Socket got from ServerSocket.accept()
     * @param callback {@link simplechat.communication.socket.server.SimpleChatServer} reference
     * @throws IOException will be throwed if the init of Input- or OutputStream fails
     */
    ClientWorker(Socket client, SimpleChatServer callback) throws IOException {
        this.callback = callback;
        this.client = client;
    }

    /**
     * MessageHandler for incoming Messages on Client Socket
     * <br>
     * The InputSocket will be read synchronous through readLine()
     * Incoming messages first will be checked if they start with any Commands, which will be executed properly.
     * Otherwise text messages will be delegated to the {@link SimpleChatServer#received(String, ClientWorker)} method.
     */
    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(this.client.getInputStream()));
            out = new PrintWriter(this.client.getOutputStream(), true);
            String message;
            if (listening) {
                while ((message = in.readLine()) != null) {
                    this.callback.received(message, this);
                }
            }
        }
        catch (IOException ex ) {
            SimpleChat.serverLogger.log(INFO, "Server is unreachable!");
        }
    }

    /**
     * Clean shutdown of ClientWorker
     * <br>
     * If listening was still true, we are sending a {@link MessageProtocol.Commands#EXIT} to the client.
     * Finally we are closing all open resources.
     */
    void shutdown() {
        SimpleChat.serverLogger.log(INFO, "Shutting down ClientWorker ... listening=" + listening);
        if (listening) {
            this.send( "!EXIT" );
            this.listening = false;
        }
        try {
            in.close();
            out.close();
            client.close();
        } catch (IOException e) {
            SimpleChat.serverLogger.log(INFO, "Fehler beim schließen des Client Sockets!");
        }
    }

    /**
     * Sending message through Socket OutputStream {@link #out}
     *
     * @param message MessageText for Client
     */
    void send(String message) {
        if (out == null) {
            try {
                Thread.sleep( 10 );
            } catch (InterruptedException e) {
                e.printStackTrace( );
            }
        }
        out.println(message);
    }
}