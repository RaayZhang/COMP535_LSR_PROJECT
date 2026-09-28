PA1 ROUTER IMPLEMENTATION NOTES
========================================================================

1. RUNNING INSTRUCTIONS
------------------------------------------------------------------------
Step 1 - Build from the comp535_sketch_code directory:

    mvn clean package assembly:single

Step 2 - Open one terminal per router, with a different router1-7.conf
file in each terminal. For example:

    java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router1.conf
    java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router2.conf

At startup, each router prints its Process IP, Process Port, and Simulated IP.
Use the destination router's printed values in an attach command.

Step 3 - PA1 commands:

    attach [Process IP] [Process Port] [Simulated IP] [Link Weight]
    start
    neighbors

Attach: the receiver answers Y or N. Acceptance stores a Link at both ends,
but the neighbor is not TWO_WAY yet. Rejection leaves no new Link.

Start: exchanges three HELLO messages. The receiver moves from INIT to
TWO_WAY; the starter reaches TWO_WAY after receiving the second HELLO.

Neighbors: prints one Simulated IP per line, only for links in TWO_WAY state.

========================================================================

2. FOUR TYPES OF THREADS
------------------------------------------------------------------------
1) Terminal thread - the only thread that reads System.in. It runs most
   commands and interprets the next input as Y/N when approval is pending.

2) Listener thread - requestHandler() waits on ServerSocket.accept() and
   gives each accepted socket to a connection-handler thread. Waiting for
   one user's answer does not stop the listener from accepting connections.

3) Connection-handler threads - connectionHandler() reads the first packet,
   dispatches an attach or HELLO exchange by packet type, then closes that
   socket when the exchange ends.

4) Outgoing attach threads - processAttach() waits for the remote Y/N answer
   on its own thread. The terminal remains free to answer an incoming attach,
   including when two routers try to attach to each other at the same time.

========================================================================

3. ORDINARY METHODS: WHAT EACH GROUP DOES
------------------------------------------------------------------------
Startup and packets

* Router() reads the configured Simulated IP, opens the listening socket,
  prints router identifiers, and starts the listener thread.
* bindFreePort() tries up to 200 ports in 10000-32766. The starter code
  stores a Process Port in a Java short; ServerSocket(0) could select a
  port above 32767 that cannot be stored as a positive short.
* newPacket() fills sender/destination fields. Packet type 3 means attach;
  type 0 means HELLO. readPacket() checks that the received object is a
  SOSPFPacket. openOutput() flushes the output-stream header before opening
  ObjectInputStream so the two sides do not wait for each other's header.

Link slots and status

* findLink() checks for an existing Link to a Simulated IP.
* reservePort() marks an empty slot; releasePort() frees a failed or
  rejected reservation; commitPort() stores an accepted Link and clears
  its reservation. A duplicate Link is not committed.
* snapshotLinks() copies the current links before start performs network
  I/O. removeLink() frees the slot holding a particular Link object.
* advanceNeighborStatus() moves a neighbor toward INIT or TWO_WAY without
  moving it backward. processNeighbors() prints only TWO_WAY links.

Attach, start, and input

* processAttach() rejects self-attachment or an existing/pending duplicate,
  reserves a slot, sends the weight in an attach packet, then commits or
  releases the slot after the reply.
* handleAttachRequest() checks the addressed Simulated IP and slot capacity,
  asks the user when needed, and sends ACCEPTED or REJECTED. askUserYesNo()
  passes the Y/N answer from terminal() to the waiting handler.
* processStart() takes a link snapshot. helloHandshake() sends, receives,
  and sends the three HELLO messages. handleHello() handles the receiving
  side and moves its neighbor through INIT to TWO_WAY.
* terminal() reads input; runCommand() chooses the command handler.
  processQuit() closes the listener and exits.

========================================================================

4. WHY A PENDING ATTACH RESERVES A PORT
------------------------------------------------------------------------
A sends attach to B and waits for B's answer. During that wait, another
request could choose the same empty slot. reservedPorts marks the slot
before waiting so the second request cannot take it. If the request fails
or B answers N, releasePort() clears the mark. If B answers Y, commitPort()
stores the Link in ports[] and clears the mark. The slot is then occupied,
not still reserved.

Why not temporarily put a Link in ports[]? start reads ports[] to choose
HELLO destinations. A pending Link there could make start try to handshake
before approval. It could also make a simultaneous reverse attach look
like an existing connection before the user has answered.

portLock protects ports[] and reservedPorts[]. snapshotLinks() lets start
use the latest copy without holding portLock while doing network I/O.
Each router has four slots; a fifth request is rejected when none is free.

========================================================================

5. SIMULTANEOUS AND DUPLICATE ATTACH REQUESTS
------------------------------------------------------------------------
A and B may each send attach to the other before either answer arrives.
Separate outgoing threads leave both terminal threads free to answer.
commitPort() keeps at most one local Link to the same Simulated IP; a
second commit returns false and the unused reservation is released.

* Both directions accepted: each router ends with one Link, not two.
* One direction accepted, the other rejected: the accepted direction can
  still establish the Link.
* Both directions rejected: neither establishes a Link.

Depending on timing, an incoming request may find an existing Link and be
accepted automatically rather than asking the user again. The
outgoingAttachRouters set blocks a duplicate outgoing attach to the same
Simulated IP while the first is pending; findLink() blocks a later attach
when the Link already exists.

========================================================================

6. APPROVAL INPUT AND OTHER EDGE CASES
------------------------------------------------------------------------
attachApprovalLock lets only one incoming handler ask for Y/N at a time.
pendingAttachDecision is a CompletableFuture<Boolean> used as a mailbox:
the handler waits; terminal() completes it after Y or N. Other input
prints a reminder and leaves the same question pending.

An attach addressed to the wrong Simulated IP is rejected automatically.
Empty terminal lines are ignored. Malformed numeric arguments print an
error; unknown commands print a message without ending the terminal.

start with no links prints a message. If an outgoing attach is still
pending, start notes that it may need to be run again after acceptance.
A missing or invalid second HELLO prevents the starter from reaching
TWO_WAY. The starter changes to TWO_WAY before sending the final HELLO;
if that last delivery fails, the two ends may temporarily disagree.

========================================================================

7. CONNECTION LIFETIME AND QUIT
------------------------------------------------------------------------
Each attach or HELLO exchange uses a new TCP socket. After attach, a Link
remembers the peer's Process IP and Process Port for later exchanges.

processQuit() closes this router's listening socket and exits. It does
not instantly notify the other routers. A peer may still list this Link
until it runs start again. If that attempt gets Connection refused,
helloHandshake() removes the Link; the next neighbors call omits it.
Other I/O failures do not necessarily remove the Link. PA1 has no
background failure detector.

========================================================================

8. PA1 SCOPE AND AI ASSISTANCE
------------------------------------------------------------------------
PA1 implements attach, the HELLO part of start, and neighbors. Link-state
database synchronization and the other routing commands belong to later
assignments and are not implemented here.

The earlier README recorded AI help with object-stream flushing, choosing
a Process Port compatible with short, checking concurrent attach edge
cases, and writing this README. The team should verify its actual use
and describe it in the instructor's separate AI-usage report.
