package socs.network.node;

import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;


public class Router {

  // ---------------------------------------------------------------------------------------
  // Packet types carried in SOSPFPacket.sospfType. 0/1/2 are defined by the starter code;
  // 3 is added for the attach request/reply so it is never mistaken for a HELLO.
  // ---------------------------------------------------------------------------------------
  // 
  static final short TYPE_HELLO = 0;     // 3-way handshake
  static final short TYPE_LSU = 1;          // linkState Update 
  static final short TYPE_APP_MESSAGE = 2;  // application message 
  static final short TYPE_ATTACH = 3;       // attach request and its reply

  // SOSPFPacket.message inside attach reply 
  static final String ATTACH_ACCEPTED = "ACCEPTED";
  static final String ATTACH_REJECTED = "REJECTED";

  // Upper bound on how long one HELLO handshake waits for the other routers (ms).
  private static final int HELLO_TIMEOUT_MS = 5000;

  protected LinkStateDatabase lsd;

  RouterDescription rd = new RouterDescription();

  // assuming that all routers are with 4 ports
  Link[] ports = new Link[4];

  // Other routers connect to this socket. It stays open until the router quits. 
  private ServerSocket serverSocket;

  // Guards ports[], reservedPorts[] 
  private final Object portLock = new Object();


   // reservedPorts[i] == true means port i is still empty but promised to an attach that is
   // waiting for a Y/N answer. Guarantees that when the answer finally is
   // "yes" there is still a free slot for the link. (We cannot simply hold portLock while
   // waiting - two routers attaching each other would then deadlock, because 
   // both side need to check port list: findLink() and that required lock)
  private final boolean[] reservedPorts = new boolean[4];

  
  // It's a lock for connection handle thread, that is currently asking Y/N question. From the
  // moment the question printed util the reply sent to the other router. Other thread wait for the 
  // lock, so we prevent the conflict.
  private final Object attachApprovalLock = new Object();

  
  // A letter box
  private volatile CompletableFuture<Boolean> pendingAttachDecision;

  // It's a HashSet that keep tracking the current attach (those haven't receive response)
  // which make sure won't have duplicate attach after the attach request been sent
  private final Set<String> outgoingAttachRouters =Collections.synchronizedSet(new HashSet<String>());

  // =======================================================================================
  // Construction / startup
  // =======================================================================================

  public Router(Configuration config) {
    rd.simulatedIPAddress = config.getString("socs.network.router.ip");
    lsd = new LinkStateDatabase(rd);

    try {
      rd.processIPAddress = InetAddress.getLocalHost().getHostAddress(); // obtain the real host IP address
      serverSocket = bindFreePort();  //can't use ServerSocket(0) here
      rd.processPortNumber = (short) serverSocket.getLocalPort();
    } catch (IOException e) {
      throw new RuntimeException("cannot start router: " + e.getMessage(), e);
    }

    System.out.println("========================================");
    System.out.println("Process IP    : " + rd.processIPAddress);
    System.out.println("Process Port  : " + rd.processPortNumber);
    System.out.println("Simulated IP  : " + rd.simulatedIPAddress);
    System.out.println("========================================");

    // Now we create an new thread for the requestHandler()
    Thread reqHandleThread = new Thread(() -> requestHandler());
    // start an new thread
    reqHandleThread.setDaemon(true);
    reqHandleThread.start();
  }


  // Method that help to find a free port on your computer and create a ServerSocket
  private static ServerSocket bindFreePort() throws IOException {
    Random random = new Random();
    for (int attempt = 0; attempt < 200; attempt++) {
      int port = 10000 + random.nextInt(Short.MAX_VALUE - 10000); // 10000 .. 32766
      try {
        return new ServerSocket(port);
      } catch (IOException portInUse) {
        // try another one
      }
    }
    throw new IOException("no free port found in range 10000-32766");
  }


  // =======================================================================================
  // Port list helpers (all access to ports[] / reservedPorts[] need these methods)
  // =======================================================================================

  /** Returns the link whose remote end has the given simulated IP, or null. */
  private Link findLink(String simulatedIP) {
    synchronized (portLock) {
      for (Link link : ports) {
        if (link != null && link.router2.simulatedIPAddress.equals(simulatedIP)) {
          return link;
        }
      }
      return null;
    }
  }

  /** Reserves the first port that is neither occupied nor reserved. Returns -1 if none. */
  private int reservePort() {
    synchronized (portLock) {
      for (int i = 0; i < ports.length; i++) {
        if (ports[i] == null && !reservedPorts[i]) {
          reservedPorts[i] = true;
          return i;
        }
      }
      return -1;
    }
  }

  /** Gives a reservation back without storing a link (attach rejected or failed). */
  private void releasePort(int port) {
    synchronized (portLock) {
      reservedPorts[port] = false;
    }
  }

  /**
   * Stores link in the reserved port and clears the reservation.
   * Returns false and stores nothing if a link to the same router already exists.
   * That happens when two routers attach each other at the same time and the other
   * direction was accepted first; keeping only the first link is what makes a simultaneous
   * attach safe. The caller must then release port from the reservation.
   */
  private boolean commitPort(int port, Link link) {
    synchronized (portLock) {
      if (!reservedPorts[port] || ports[port] != null) {
      throw new IllegalStateException("Port not reserved or it's unexpectedly occupied.");
    }
      if (findLink(link.router2.simulatedIPAddress) != null) {
        return false;
      }
      ports[port] = link;
      reservedPorts[port] = false;
      return true;
    }
  }

  /** Copy of all links, so callers can do network I/O without holding portLock.
   *  It make sure you copy the latest list, but after that the list could be updated
   */
  private List<Link> snapshotLinks() {
    synchronized (portLock) {
      List<Link> result = new ArrayList<>();
      for (Link link : ports) {
        if (link != null) {
          result.add(link); // we get it's ref
        }
      }
      return result;
    }
  }

  /**
   * Removes exactly this link object from the port table and frees its port.
   * Compares by reference (ports[i] == link)
   */
  private boolean removeLink(Link link) {
    synchronized (portLock) {
      for (int i = 0; i < ports.length; i++) {
        if (ports[i] == link) {
          ports[i] = null;
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Moves the neighbour's state forward (null -> INIT -> TWO_WAY) and prints the transition msg.
   * Never moves backwards!!! so running start again on an already TWO_WAY neighbour
   * does not print any msg.
   */
  private void advanceNeighborStatus(Link link, RouterStatus target) {
    boolean changed;
    synchronized (portLock) {
      RouterStatus current = link.router2.status;
      changed = current == null || current.ordinal() < target.ordinal();
      if (changed) { // check if the changed of status is legal
        link.router2.status = target;
      }
    }
    if (changed) {
      System.out.println("set " + link.router2.simulatedIPAddress + " STATE to " + target + ";");
    }
  }

  // =======================================================================================
  // Packet / stream helpers
  // =======================================================================================

  /** A packet of the given type with all fields filled in. */
  private SOSPFPacket newPacket(short typeMessage, String dstSimulatedIP) {
    SOSPFPacket packet = new SOSPFPacket();
    packet.sospfType = typeMessage;
    packet.srcProcessIP = rd.processIPAddress;
    packet.srcProcessPort = rd.processPortNumber;
    packet.srcIP = rd.simulatedIPAddress;
    packet.dstIP = dstSimulatedIP;
    packet.routerID = rd.simulatedIPAddress; // ? not sure
    packet.neighborID = rd.simulatedIPAddress; // sender's simulated IP 
    return packet;
  }

  /**
   * Opens the object output stream and pushes its header to the peer right away.
   * IMPORTANT : An ObjectInputStream constructor blocks until it has
   * read the header written by the peer's ObjectOutputStream; if both sides opened their
   * input stream first they would wait for each other forever.
   */
  private static ObjectOutputStream openOutput(Socket socket) throws IOException {
    ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
    out.flush();
    return out;
  }

  /** Reads one SOSPFPacket; anything else on the wire is treated as an I/O error. */
  private static SOSPFPacket readPacket(ObjectInputStream in)
      throws IOException, ClassNotFoundException {
    Object received = in.readObject();
    if (!(received instanceof SOSPFPacket)) {
      throw new IOException("unexpected object on the wire: " + received.getClass().getName());
    }
    return (SOSPFPacket) received;
  }

  // =======================================================================================
  // Incoming connections
  // =======================================================================================

  /**
   * Listener loop: accept a connection and hand it to a fresh thread, so that a request
   * that takes a long time (e.g. waiting for the user's Y/N) never blocks other routers.
   */
  private void requestHandler() {
    while (!serverSocket.isClosed()) {
      try {
        Socket socket = serverSocket.accept();
        Thread handler = new Thread(() -> connectionHandler(socket));
        handler.setDaemon(true);
        handler.start();
      } catch (IOException e) {
        if (serverSocket.isClosed()) {
          return; // quit was called
        }
        System.out.println("Accept failed: " + e.getMessage());
      }
    }
  }

  /** Reads the first packet of a connection and dispatches on its type. */
  private void connectionHandler(Socket socket) {
    try (Socket s = socket;
         ObjectOutputStream out = openOutput(s);
         ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {

      SOSPFPacket packet = readPacket(in);
      switch (packet.sospfType) {
        case TYPE_ATTACH:
          handleAttachRequest(packet, out);
          break;
        case TYPE_HELLO:
          handleHello(packet, s, in, out);
          break;
        default:
          System.out.println("received unsupported packet type " + packet.sospfType
              + " from " + packet.srcIP + ";");
      }
    } catch (IOException | ClassNotFoundException e) {
      System.out.println("connection error: " + e.getMessage());
    }
  }

  // =======================================================================================
  // Attach
  // =======================================================================================

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * NOTE: this command should not trigger link database synchronization
   */

  private void processAttach(String processIP, short processPort,
                             String simulatedIP, short weight) {
    if (rd.simulatedIPAddress.equals(simulatedIP)) {
      System.out.println("Cannot attach to yourself.");
      return;
    }
    if (findLink(simulatedIP) != null) {
      System.out.println("Cannot attach: a link to " + simulatedIP + " already exists.");
      return;
    }
    // check if we have already sent the attach request
    if (!outgoingAttachRouters.add(simulatedIP)) {// fail to add it
      System.out.println("An attach request to " + simulatedIP + " is already pending.");
      return;
    }

    int port = -1;
    boolean committed = false;
    try {
      port = reservePort();
      if (port == -1) {
        System.out.println("Cannot attach: all ports are in use.");
        return;
      }

      try (Socket socket = new Socket(processIP, processPort);
           ObjectOutputStream out = openOutput(socket);
           ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

        SOSPFPacket request = newPacket(TYPE_ATTACH, simulatedIP);
        request.weight = weight;
        out.writeObject(request);
        out.flush();

        // Blocks until the remote user has typed Y or N.
        SOSPFPacket reply = readPacket(in);

        if (ATTACH_ACCEPTED.equals(reply.message)) {
          RouterDescription remote = new RouterDescription();
          remote.processIPAddress = processIP;
          remote.processPortNumber = processPort;
          remote.simulatedIPAddress = simulatedIP;
          remote.status = null; // attached, but no HELLO exchanged yet
          committed = commitPort(port, new Link(rd, remote, weight)); // add link from reserve ports to ports
          System.out.println("Your attach request has been accepted;");
        } else if (findLink(simulatedIP) != null) {
          // Simultaneous attach: the peer rejected our request, but we accepted theirs.
          System.out.println("Your attach request has been rejected;"
              + " (a link to " + simulatedIP + " already exists from the other direction)");
        } else { 
          System.out.println("Your attach request has been rejected;");
        }
      }
    } catch (IOException | ClassNotFoundException e) {
      System.out.println("Attach failed: " + e.getMessage());
    } finally {
      if (port != -1 && !committed) {
        releasePort(port); // release the reserved slot
      }
      outgoingAttachRouters.remove(simulatedIP);
    }
  }

  /**
   * process request from the remote router.
   * For example: when router2 tries to attach router1. Router1 can decide whether it will accept this request.
   * Runs on a connection handler thread. Asks the user through askUserYesNo()
   * stores the link on "Y", and always sends exactly one reply to the requester.
   */
  private void handleAttachRequest(SOSPFPacket request, ObjectOutputStream out) throws IOException {
    // initialise an new packet and let the msg = rejected
    SOSPFPacket reply = newPacket(TYPE_ATTACH, request.srcIP);
    reply.message = ATTACH_REJECTED;

    // Only one request ask the user at a time. The lock is held until the reply has
    // been sent, so a second request can never pick up the answer before the first one.
    synchronized (attachApprovalLock) {
      int port = -1;
      boolean committed = false;
      try {
        if (!rd.simulatedIPAddress.equals(request.dstIP)) {
          // The requester typed the wrong simulated IP for this process.
          System.out.println("received attach request from " + request.srcIP
              + " addressed to " + request.dstIP + " (not me); rejected automatically;");

        } else if (findLink(request.srcIP) != null) {
          // Simultaneous attach: the other direction was already accepted. The link exists,
          // so just confirm it instead of asking the user again.
          System.out.println("received HELLO from " + request.srcIP
              + "; a link already exists, accepted automatically;");
          reply.message = ATTACH_ACCEPTED;

        } else if ((port = reservePort()) == -1) { // there no free port
          System.out.println("received HELLO from " + request.srcIP
              + "; all ports are in use, rejected automatically;");

        } else {
          System.out.println("received HELLO from " + request.srcIP + ";");
          System.out.println("Do you accept this request? (Y/N)");

          if (askUserYesNo()) {
            RouterDescription remote = new RouterDescription();
            remote.processIPAddress = request.srcProcessIP;
            remote.processPortNumber = request.srcProcessPort;
            remote.simulatedIPAddress = request.srcIP;
            remote.status = null; // attached, but no HELLO exchanged yet
            committed = commitPort(port, new Link(rd, remote, request.weight));
            reply.message = ATTACH_ACCEPTED;
            System.out.println("You accepted the attach request;");
          } else {
            System.out.println("You rejected the attach request;");
          }
        }
      } finally {
        if (port != -1 && !committed) {
          releasePort(port);
        }
      }

      out.writeObject(reply);
      out.flush();
    }
  }

  /**
   * Publishes a decision box for the terminal thread and blocks until the user has typed
   * Y or N. Must be called while holding attachApprovalLock.
   */
  private boolean askUserYesNo() {
    CompletableFuture<Boolean> decision = new CompletableFuture<>();
    pendingAttachDecision = decision; 
    // it could be checked by other thread. At each iteration of the terminal thread
    // it would check if they receive this box.  Once they recieve they need to handle the request
    // and give an response
    try {
      return decision.get();
    } catch (InterruptedException | ExecutionException e) {
      return false;
    }
  }

  // =======================================================================================
  // start (HELLO handshake)
  // =======================================================================================

  /**
   * broadcast Hello to neighbors
   * <p/>
   * Performs the three-message HELLO handshake with every attached router. Each handshake
   * is short and needs no user input, so this runs directly on the terminal thread.
   */
  private void processStart() {
    List<Link> links = snapshotLinks();
    if (links.isEmpty()) {
      System.out.println("No attached routers; run attach first.");
      return;
    }
    for (Link link : links) {
      helloHandshake(link);
    }
    if (!outgoingAttachRouters.isEmpty()) {
      System.out.println("Note: some attach requests are still waiting for an answer;"
          + " run start again once they are accepted.");
    }
  }

  /**
   * Initiator side of the handshake 
   * send HELLO, receive HELLO (neighbour becomes TWO_WAY here), send HELLO again.
   */
  private void helloHandshake(Link link) {
    RouterDescription neighbor = link.router2;
    try (Socket socket = new Socket(neighbor.processIPAddress, neighbor.processPortNumber);
         ObjectOutputStream out = openOutput(socket);
         ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
      socket.setSoTimeout(HELLO_TIMEOUT_MS); // setup the terminate time

      // 1st HELLO
      out.writeObject(newPacket(TYPE_HELLO, neighbor.simulatedIPAddress));
      out.flush();

      // 2nd HELLO (the neighbour's answer)
      SOSPFPacket reply = readPacket(in); 
      if (reply.sospfType != TYPE_HELLO || !neighbor.simulatedIPAddress.equals(reply.srcIP)) {
        System.out.println("start: unexpected reply from " + neighbor.simulatedIPAddress + ";");
        return;
      }
      System.out.println("received HELLO from " + reply.srcIP + ";");
      advanceNeighborStatus(link, RouterStatus.TWO_WAY);

      // 3rd HELLO, so the neighbour also reaches TWO_WAY
      out.writeObject(newPacket(TYPE_HELLO, neighbor.simulatedIPAddress));
      out.flush();

    } catch (ConnectException e) {
      // "Connection refused": no process is listening on the neighbour's port any more, so
      // it has quit. Drop the dead link so the port is freed 
      // Other errors (eg: timeout) keep the link, the neighbour may only be slow.
      if (removeLink(link)) {
        System.out.println("start: " + neighbor.simulatedIPAddress
            + " is unreachable (connection refused); link removed;");
      }
    } catch (IOException | ClassNotFoundException e) {
      System.out.println("start: cannot reach " + neighbor.simulatedIPAddress
          + ": " + e.getMessage());
    }
  }

  /**
   * Responder side of the handshake, running on a connection-handler thread:
   * got HELLO (neighbour becomes INIT), send HELLO, wait for the final HELLO
   * (neighbour becomes TWO_WAY).
   */
  private void handleHello(SOSPFPacket first, Socket socket,
                           ObjectInputStream in, ObjectOutputStream out)
      throws IOException, ClassNotFoundException {
    Link link = findLink(first.srcIP); // check if the connection exist
    if (link == null) {
      System.out.println("received HELLO from " + first.srcIP + " but it is not attached; ignored;");
      return;
    }

    System.out.println("received HELLO from " + first.srcIP + ";");
    advanceNeighborStatus(link, RouterStatus.INIT); // before attach it's null, at the first time it receive Hello it become INIT 

    out.writeObject(newPacket(TYPE_HELLO, first.srcIP));
    out.flush();

    socket.setSoTimeout(HELLO_TIMEOUT_MS);
    SOSPFPacket third = readPacket(in);
    if (third.sospfType != TYPE_HELLO) {
      System.out.println("start: unexpected packet from " + first.srcIP + ";");
      return;
    }
    System.out.println("received HELLO from " + third.srcIP + ";");
    advanceNeighborStatus(link, RouterStatus.TWO_WAY);
  }

  // =======================================================================================
  // neighbors / quit
  // =======================================================================================

  /**
   * output the neighbors of the routers
   */
  private void processNeighbors() {
    List<String> neighbors = new ArrayList<>();
    synchronized (portLock) {
      for (Link link : ports) {
        if (link != null && link.router2.status == RouterStatus.TWO_WAY) {
          neighbors.add(link.router2.simulatedIPAddress);
        }
      }
    }
    for (String ip : neighbors) {
      System.out.println(ip);
    }
  }

  /**
   * disconnect with all neighbors and quit the program
   */
  private void processQuit() {
    try {
      serverSocket.close();
    } catch (IOException ignored) {
      // nothing useful to do while exiting
    }
    System.exit(0);
  }

  // =======================================================================================
  // Commands for later assignments (unchanged from the starter code)
  // =======================================================================================

  /**
   * output the shortest path to the given destination ip
   * <p/>
   * format: source ip address  -> ip address -> ... -> destination ip
   *
   * @param destinationIP the ip adderss of the destination simulated router
   */
  private void processDetect(String destinationIP) {

  }

  /**
   * disconnect with the router identified by the given destination ip address
   * Notice: this command should trigger the synchronization of database
   *
   * @param portNumber the port number which the link attaches at
   */
  private void processDisconnect(short portNumber) {

  }

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * This command does trigger the link database synchronization
   */
  private void processConnect(String processIP, short processPort,
                              String simulatedIP, short weight) {

  }

  /**
   * update the weight of an attached link
   */
  private void updateWeight(String processIP, short processPort,
                            String simulatedIP, short weight) {

  }

  /**
   * update the weight of a specific port.
   * This change should trigger synchronization of the Link State Database by sending
   * a Link State Advertisement (LSA) update to all neighboring routers in the topology.
   *
   * @param portNumber the port number (0-3) to update
   * @param newWeight the new weight/cost for the link attached to this port
   */
  private void processUpdate(short portNumber, short newWeight) {

  }

  /**
   * send an application-level message from this router to the destination router.
   * The message must be forwarded hop-by-hop according to the current shortest path.
   * <p/>
   * When you run send, the window of the router where you run the command should print:
   * "Sending message to <Destination IP>"
   * <p/>
   * For each intermediate router on the shortest path (excluding the source and destination),
   * the router window should print:
   * "Forwarding packet from <Source IP> to <Destination IP>"
   * <p/>
   * When the destination router receives the message, the router window should print:
   * "Received message from <Source IP>:"
   * "<Message>"
   *
   * @param destinationIP the simulated IP address of the destination router
   * @param message the message content to send
   */
  private void processSend(String destinationIP, String message) {

  }

  /**
   * handle incoming application message packet.
   * This method should be called when a router receives a SOSPFPacket with sospfType = 2 (Application Message).
   * <p/>
   * If this router is the destination (packet.dstIP equals this router's simulatedIPAddress):
   * - Print "Received message from <Source IP>:"
   * - Print the message content
   * <p/>
   * If this router is an intermediate router:
   * - Print "Forwarding packet from <Source IP> to <Destination IP>"
   * - Forward the packet to the next hop on the shortest path to the destination
   * - Do NOT print or inspect the message payload
   *
   * @param packet the received application message packet
   */
  private void handleApplicationMessage(socs.network.message.SOSPFPacket packet) {

  }

  // =======================================================================================
  // Terminal
  // =======================================================================================

  /**
   * Command loop of the terminal thread. Every iteration reads one line; if a remote attach
   * request is waiting for an answer, that line is the Y/N answer, otherwise it is a command.
   */
  public void terminal() {
    BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
    try {
      while (true) {
        System.out.print(">> ");
        String command = br.readLine();
        if (command == null) {
          processQuit(); // end of input (Ctrl-D / Ctrl-Z): behave like quit
          return;
        }
        command = command.trim();
        if (command.isEmpty()) {
          continue;
        }

        // The line answers a pending attach request, not a command.
        CompletableFuture<Boolean> decision = pendingAttachDecision;
        if (decision != null) {
          if (command.equalsIgnoreCase("Y")) {
            // Clear the box BEFORE completing it: the waiting handler wakes up on complete()
            // and may immediately publish the next question, which must not be wiped out.
            pendingAttachDecision = null;
            decision.complete(true);
          } else if (command.equalsIgnoreCase("N")) {
            pendingAttachDecision = null;
            decision.complete(false);
          } else {
            System.out.println("Please enter Y or N.");
          }
          continue;
        }

        try {
          runCommand(command);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
          System.out.println("Invalid arguments: " + command);
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** Parses one command line and runs the matching process* method. */
  private void runCommand(String command) {
    if (command.startsWith("detect ")) {
      String[] cmdLine = command.split(" ");
      processDetect(cmdLine[1]);
    } else if (command.startsWith("disconnect ")) {
      String[] cmdLine = command.split(" ");
      processDisconnect(Short.parseShort(cmdLine[1]));
    } else if (command.equals("quit")) {
      processQuit();
    } else if (command.startsWith("attach ")) {
      // attach [Process IP] [Process Port] [Simulated IP] [Link Weight]
      String[] cmdLine = command.split(" ");
      String processIP = cmdLine[1];
      short processPort = Short.parseShort(cmdLine[2]);
      String simulatedIP = cmdLine[3];
      short weight = Short.parseShort(cmdLine[4]);
      // Runs off the terminal thread: it blocks until the remote user answers Y/N, and this
      // terminal must stay free to answer the remote router's own request in the meantime.
      Thread attach = new Thread(
          () -> processAttach(processIP, processPort, simulatedIP, weight),
          "attach-" + simulatedIP);
      attach.setDaemon(true);
      attach.start();
    } else if (command.equals("start")) {
      processStart();
    } else if (command.startsWith("connect ")) {
      String[] cmdLine = command.split(" ");
      processConnect(cmdLine[1], Short.parseShort(cmdLine[2]),
          cmdLine[3], Short.parseShort(cmdLine[4]));
    } else if (command.equals("neighbors")) {
      processNeighbors();
    } else if (command.startsWith("send ")) {
      // send [Destination IP] [Message]
      String[] cmdLine = command.split(" ", 3);
      if (cmdLine.length >= 3) {
        processSend(cmdLine[1], cmdLine[2]);
      } else {
        System.out.println("Usage: send [Destination IP] [Message]");
      }
    } else if (command.startsWith("update ")) {
      // update [port_number] [new_weight]
      String[] cmdLine = command.split(" ");
      if (cmdLine.length >= 3) {
        processUpdate(Short.parseShort(cmdLine[1]), Short.parseShort(cmdLine[2]));
      } else {
        System.out.println("Usage: update [port_number] [new_weight]");
      }
    } else {
      System.out.println("Unknown command: " + command);
    }
  }

}
