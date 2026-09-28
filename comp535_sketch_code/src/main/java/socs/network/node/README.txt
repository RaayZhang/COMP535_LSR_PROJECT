PA1 IMPLEMENTATION NOTES

Build and run
From the comp535_sketch_code directory, run:

    mvn clean package assembly:single
    java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router1.conf

Open one terminal per router, using a different router1-7.conf file in each.
The startup output shows its Process IP, Process Port, and Simulated IP. Use the
remote router's printed values in this command:

    attach [Process IP] [Process Port] [Simulated IP] [Link Weight]

The receiver answers Y or N. Acceptance records a Link at both ends, but does
not make either end TWO_WAY. Run start on an attached router to exchange three
HELLO messages. The receiver moves through INIT to TWO_WAY; the starter moves
to TWO_WAY after receiving the second HELLO. neighbors prints only the
simulated IPs of links currently in TWO_WAY state, one per line.

How the program is organized

* The terminal thread is the only reader of System.in. It processes most commands
  and treats the next input line as Y/N when an incoming attach needs approval.
* The listener thread waits on ServerSocket.accept() and gives every incoming
  socket to its own connectionHandler() thread. A slow request does not block
  the listener from accepting another connection.
* A connection-handler thread reads a SOSPFPacket, dispatches by sospfType,
  handles one attach or HELLO exchange, and closes that socket afterward.
* Each outgoing attach has its own thread because it may wait for the other
  router's Y/N response. The terminal remains available to answer the other
  router if both routers send attach requests around the same time.

Important methods and fields

* Router() reads the configured Simulated IP, opens the listening socket,
  prints the three router identifiers, and starts the listener thread.
* bindFreePort() tries up to 200 random ports from 10000 through 32766.
  The starter code's process-port field is a Java short, so an unrestricted
  ServerSocket(0) could choose a port this version cannot store in that field.
* findLink() looks for an existing Link by remote Simulated IP. portLock protects
  accesses to ports and reservedPorts; snapshotLinks() makes a copy for start
  without holding that lock during network I/O.
* reservePort() marks one empty slot as pending. releasePort() clears the mark
  after a rejection or failure. commitPort() puts an accepted Link into that
  slot and clears the mark; if a Link to the same router already exists, it
  returns false and the caller releases the unused reservation.
* removeLink() removes exactly the matching Link object. advanceNeighborStatus()
  moves a neighbor forward toward INIT or TWO_WAY without moving it backward.
* newPacket() fills the sender and destination fields of a SOSPFPacket.
  openOutput() flushes the ObjectOutputStream header before either side opens
  ObjectInputStream; otherwise both sides could wait for a stream header.
  readPacket() rejects an object that is not a SOSPFPacket.
* requestHandler() accepts sockets; connectionHandler() routes packet type 3
  to handleAttachRequest() and type 0 to handleHello(). Each later exchange
  opens a new socket using the Process IP and Process Port stored in the Link.
* processAttach() validates the destination, reserves a slot, sends an attach
  packet containing the link weight, waits for a reply, and commits or releases
  the slot. handleAttachRequest() checks the destination and free slots,
  asks the user when needed, and sends an accept/reject packet.
* askUserYesNo() publishes pendingAttachDecision, a CompletableFuture<Boolean>
  used like a mailbox between the handler and terminal threads. A response
  other than Y or N leaves the question pending so the user can try again.
  attachApprovalLock allows only one incoming approval question at a time.
* processStart() takes a snapshot of attached links. helloHandshake() sends
  HELLO, reads the reply, and sends the final HELLO. handleHello() processes
  those messages on the receiver and advances the neighbor state.
* processNeighbors() prints only TWO_WAY neighbors. processQuit() closes the
  listening socket and exits. terminal() reads commands; runCommand() parses
  them and starts the appropriate method.

Edge cases handled by this code

* Each router has four Link slots. A fifth incoming or outgoing attach cannot
  reserve a slot and is rejected. A rejected or failed attach frees its slot.
* A pending attach is only marked in reservedPorts; it is not temporarily put
  into ports. Therefore start and neighbors cannot mistake a pending request
  for an established link.
* outgoingAttachRouters tracks pending outgoing Simulated IPs and stops a
  duplicate outgoing attach to the same router while the first is waiting.
  findLink() also stops a new attach when that link already exists.
* Two routers can send attach requests to each other around the same time.
  Their terminal threads can still answer Y/N. commitPort() keeps at most one
  local Link to the same Simulated IP and releases a losing reservation. If
  one direction is rejected but the other accepted, the accepted direction
  can still establish a link; if both are rejected, neither establishes one.
  Depending on timing, a request to a router that already has the Link may
  be accepted automatically rather than asking the user again.
* An attach addressed to the wrong Simulated IP is rejected automatically.
  An empty command is ignored, malformed numeric arguments print an error,
  and an unknown command prints a message without ending the terminal loop.
* start with no attached links prints a message. If an outgoing attach is
  still pending, start notes that it may need to be run again after approval.
  A missing or invalid second HELLO prevents the starter from reaching TWO_WAY.
  After the starter reaches TWO_WAY, a failure to deliver the final HELLO is not
  rolled back, so the two ends may temporarily disagree about the state.
* There is no background failure detector in PA1. A neighbor that quits may
  remain listed until this router runs start again. If that connection attempt
  gets Connection refused, helloHandshake() removes the Link; the next
  neighbors command no longer lists it. Other I/O failures do not necessarily
  remove the Link.

Scope and AI disclosure

PA1 covers attach, the HELLO part of start, and neighbors. Link-state database
synchronization and the other routing commands are for later assignments and
are not implemented here. The earlier README said AI was consulted about
object-stream flushing, process-port selection, concurrent attach edge cases,
and README wording. The team should verify its actual use and describe it in
the separate AI-usage report required by the instructor.
