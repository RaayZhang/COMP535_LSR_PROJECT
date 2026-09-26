
Running Instruction:

Step1: On Windows PowerShell, from the `comp535_sketch_code` directory:

$cfg  = "$env:USERPROFILE\.m2\repository\com\typesafe\config\1.3.1\config-1.3.1.jar"
$srcs = Get-ChildItem -Recurse src\main\java -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -d out -cp $cfg $srcs

Step2: Each router is a separate process, so open one terminal per router. Every terminal must
use a different configuration file (router1-7.conf):

java -cp "out;$env:USERPROFILE\.m2\repository\com\typesafe\config\1.3.1\config-1.3.1.jar" socs.network.Main conf\router1.conf  

Step3: Commands

## Attach
`attach [Process IP] [Process Port] [Simulated IP] [Weight]`
eg:  >> attach 192.168.0.50 23023 192.168.1.1 2
Requests a link to another router, using the values that the other router printed on startup.

## Start
Performs the HELLO handshake with every attached router and moves each neighbor to the`TWO_WAY` state.

## Neighbors
Prints the simulated IP of every neighbor in the `TWO_WAY` state, one per line

=============================================================================================================

### 4 Threading type:
----------------------------------------------------------------------------------------------
   1:Terminal thread : the only thread that read System.in. 
        It runs most commands directly. When another router is waiting
        for our Y/N answer to an attach request, the next line typed is treated as that answer.
------------------------------------------------------------------------------------------
   2:Listener thread : loops on serverSocket and transfer every accepted socket to a new
      connection handler thread, so the listener itself is never blocked.
-----------------------------------------------------------------------------------------
   3:Connection handler threads : connectionHandler(Socket) for each 
       incoming TCP connection. They read the first packet and dispatch them base on its type
      (eg: attach equest or HELLO msg), then close the socket when this process end.
--------------------------------------------------------------------------------------------
    4:Attach threads :  the attach command runs in its own thread because it blocks until the 
        remote user give Y or N as reply. If it ran on the terminal thread, 
        two routers attaching each other at the same time could never
        answer each other's question (both terminals would be blocked).



=========================================================================================================



### Why We Reserve Ports

When router A sends an attach request to router B, A must wait for B's response before
 the link can be established. During this wait, another attach request—for example, 
 from A to C or from C to A may try to use the same free port. If that port is not reserved,
  both requests could select it, and the first request would have nowhere to store its link when B accepts.
To prevent this race condition, we reserve a free port as soon as we send an attach request. 
Other requests cannot use that port while the response is pending. If the request is rejected or fails,
 we release the reservation. If the connection accepted we just keep the position to be reserved.

 ============================================================================================================================

### Why We Not Store The Link In "Link[] port" Temporaily As An Reservation

Problem 1: There are other commands that could read the ports. For example the start, when we call this command, it
 would read though all avaliable connection in "Link[] port" (if A attach B haven't receive response but the
  connection reserved inside "Link[] port" temporaily) and it will start 3-way handshake with all connection, but 
  later it would find the connection between A and B haven't been established yet. 

Problem 2: When A attach B and B attach A happen almost at the same time, it could have the problem of 
inconsistent (eg: When B attach A, before A make the decision it notice that the connection already existed inside ports)

================================================================================================================================

### How To Handle the Situation that A attach B and B attach A happen at almost the same time

When this 2 direction attach happen there 3 possible cases: (1: A->Yes, B->Yes => both routers will have a link and the
 last one will be removed, At the beginning both link {l1: created by A->B}, {l2: created by B->A} are inside the reserve port.
 Later they would both use the commitPort() to add the link into the "Link[] port", as the commitPort would be call 2 times, the first
 time can't find the existed link so it could store the link. While the second one will find the link already existed so it do nothing just 
 return false.), (2: A->Yes, B->No => the connection will still be established), (3: A->No, B->No => just no link)

==============================================================================================================================

### How to handle the situation when one router quit and the connection still existed in others link list:

When we call the "start" command it would handle with this exception and print Connection refused. Later it could be removed from the link list.
Next time when we use the "neighbors" command you won't see that connection.

===============================================================================================================================
### Purpose of "Set<String> outgoingAttachRouters"

The outgoingAttachRouters is a synchronized HashSet, it allow us to track those pending attach. And not allow
the duplicate command (eg: A attach B) when the A attach B command is still in pending. 

===============================================================================================================================

### Purpose of "Object attachApprovalLock"

 This lock held by the connection handler thread that is currently asking the user a Y/N question, thus only one
 thread can ask for the Y/N at the same time. Other incoming attach requests wait for the lock, so questions never conflict.
 Once the thread receive reponse from the user, add the link and reply to the other node it will release the lock.

=================================================================================================================================

### Purpose of "CompletableFuture<Boolean> pendingAttachDecision"

Play the role like a letterbox, when the handler thread call askUserYesNo(), it will show the question and assign the pendingAttachDecision
with an new CompletableFuture<>() object. Once user give the reply the console thread would check if this letterbox is empty/null. If it's not null then it would 
check the reply message is yes or no, and then reassign the pendingAttachDecision = null. If the reply is illegal, it just keep it at the same status
and wait for the next loop to wait for Y/N.

=================================================================================================================================



### Connection Protocol

Each connection carry exactly one exchange utile it closed ; after attach we only remember the peer's
process IP and port in the Link, so any later exchange (eg: start) simply use these info to create
 an new Socket for connection.
 


 AI advice: 

 1: Why we flush first -> peer's ObjectInputStream could immediately receive msg
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


  2: Why Not Socket(0) directly
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

  3: AI help us to write the readme document.

  4: Ask AI to check if there any edge case we didn't think about
