package socs.network.node;
import socs.network.util.Configuration;
import socs.network.message.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*; 
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;


public class Router {

	protected LinkStateDatabase lsd;
	RouterDescription rd = new RouterDescription();

	boolean started = false;

	//assuming that all routers are with 4 ports
	Link[] ports = new Link[4];	
	ServerSocket server;

	//construct router instance
	public Router(Configuration config) {

		//fill router description (rd)
		rd.processIPAddress = "127.0.0.1";  
		rd.processPortNumber = (short) Integer.parseInt(config.getString("socs.network.router.port"));    
		rd.simulatedIPAddress = config.getString("socs.network.router.ip");
		rd.status = null;



		//initialize link state database for this router
		lsd = new LinkStateDatabase(rd);

		//create ServerSocket instance
		try {		
			server = new ServerSocket(rd.processPortNumber);
		} catch (IOException e) {
			e.printStackTrace();
		}

		//create new Server using the above socket instance
		Thread t = new Thread(new Server(this.server, this.ports, this.rd, this.lsd));
		t.start();
	}

	/**
	 * output the shortest path to the given destination ip
	 * <p/>
	 * format: source ip address  -> ip address -> ... -> destination ip
	 *
	 * @param destinationIP the ip adderss of the destination simulated router
	 */
	private void processDetect(String destinationIP) {
		 String path = lsd.getShortestPath(destinationIP);
         System.out.println(path);
	}

	/**
	 * disconnect with the router identified by the given destination ip address
	 * Notice: this command should trigger the synchronization of database
	 *
	 * @param portNumber the port number which the link attaches at
	 */
	private void processDisconnect(short portNumber) {
        
		if(portNumber > -1 && portNumber < 4){
			if(this.ports[portNumber] != null ){

				for(LinkDescription ld : lsd._store.get(rd.simulatedIPAddress).links){
					if(ld.linkID == this.ports[portNumber].router2.simulatedIPAddress){
				        lsd._store.get(rd.simulatedIPAddress).links.remove(ld);
						for(LinkDescription l  : lsd._store.get(rd.simulatedIPAddress).links){
						//	System.out.println(l + "\n");
						}
						
						break;
					}
				}


				//send LSA update
				LSAUPDATEMessage();

				//detach the link
				this.ports[portNumber] = null;
			}
		}
	}

	/**
	 * attach the link to the remote router, which is identified by the given simulated ip;
	 * to establish the connection via socket, you need to indentify the process IP and process Port;
	 * additionally, weight is the cost to transmitting data through the link
	 * <p/>
	 * NOTE: this command should not trigger link database synchronization
	 */
	private void processAttach(String processIP, short processPort,
			String simulatedIP, short weight) {

		//check if already attached
		for (int i = 0 ; i < this.ports.length; i++){
			if (this.ports[i] != null) {
				if (this.ports[i].router2.simulatedIPAddress.equals(simulatedIP) && this.ports[i].router2.processPortNumber == processPort) {
					return;
				}
			}
		}  
		//if not attached already, look for empty port
		for(int i = 0 ; i < this.ports.length; i++) {
			if (this.ports[i] == null) {
				RouterDescription neighbor = new RouterDescription();	
				neighbor.processIPAddress = processIP;
				neighbor.processPortNumber = processPort;
				neighbor.simulatedIPAddress = simulatedIP;
				this.ports[i] = new Link(this.rd, neighbor, weight);
				System.out.print("Attached router " + neighbor.simulatedIPAddress + " to " + this.rd.simulatedIPAddress + "\n");
				return; 
			}
		} 
		//otherwise
		System.out.print("All ports occupied.\n");
	}

	/**
	 * broadcast Hello to neighbors
	 */
	private void processStart() {
        int index = 0;
		started = true;
		for (Link link : this.ports) {

			//no neighbor
			if(link == null) continue;
			//TWO_WAY already established on link
			if(link.router2.status != null && link.router2.status.equals(RouterStatus.TWO_WAY)) continue;
			//otherwise start connecting
			try {

				//create new SOSPFPacket to send HELLO to neighbor
				SOSPFPacket message = new SOSPFPacket();
				message.srcProcessIP = rd.processIPAddress;
				message.srcProcessPort = rd.processPortNumber;
				message.sospfType = 0;
				message.routerID = rd.simulatedIPAddress;
				message.srcIP = rd.simulatedIPAddress;
				message.dstIP = link.router2.simulatedIPAddress;
				message.neighborID = link.router2.simulatedIPAddress;
				message.weight = link.weight;

				//create socket to connect to server
				Socket server = new Socket(link.router2.processIPAddress, link.router2.processPortNumber);

				// write to neighbor and receive reply
				ObjectOutputStream out = new ObjectOutputStream(server.getOutputStream());
				out.writeObject(message);
				ObjectInputStream in = new ObjectInputStream(server.getInputStream());
				SOSPFPacket reply = (SOSPFPacket) in.readObject();

				//if sospfType of reply is 0
				if (reply.sospfType == 0) {				
					System.out.println("received Hello from " + reply.srcIP + ";\n");
					link.router2.status = RouterStatus.TWO_WAY;
					// send message again
					out.writeObject(message);
					System.out.println("set " + link.router2.simulatedIPAddress + " state to " + link.router2.status + ";\n");
				}
				if (reply.sospfType == -1) {
					System.out.println("Failed to link. No available empty ports.\n");
					this.ports[index] = null;
					//continue;
				}

				in.close();
				out.close();
				server.close();

			}catch (ClassNotFoundException e) {
				e.printStackTrace();
			}catch (  IOException  e ){
				//e.printStackTrace();
				System.out.println("Router " + link.router2.simulatedIPAddress + " does not exist.");
				this.ports[index] = null;
			}

			//System.out.println("Iter " + k);
			index++;
		}

		//add connected neighbors to LSA of current router
		try{
			for (Link l : this.ports) {
				//if two way connection established, add link to LSA instance of router
				if (l != null && l.router2.status.equals(RouterStatus.TWO_WAY)) {
					LinkDescription ld = new LinkDescription();
					ld.linkID = l.router2.simulatedIPAddress;
					ld.portNum = l.router2.processPortNumber;
					ld.tosMetrics = l.weight;
					lsd._store.get(rd.simulatedIPAddress).links.add(ld);	
				//	System.out.println("Storing in own");
				}
			}
		}catch(Exception e) {
			e.printStackTrace();
		}

		//broadcast LSAUPDATE message
		LSAUPDATEMessage();

	}

	//send LSAUPDATE message to all neighbors
	private void LSAUPDATEMessage() {

		for (int i = 0 ; i < 4; i++) {

		  Link link = this.ports[i];

		  if(link != null && link.router2.status.equals(RouterStatus.TWO_WAY)){
			 // System.out.println("Sending update from " + rd.simulatedIPAddress + " to " + link.router2.simulatedIPAddress);
				try {

					//create new SOSPFPacket to send LSAUPDATE to neighbor
					SOSPFPacket message = new SOSPFPacket();
					message.srcProcessIP = rd.processIPAddress;
					message.srcProcessPort = rd.processPortNumber;
					message.sospfType = 1;
					message.routerID = rd.simulatedIPAddress;
					message.srcIP = rd.simulatedIPAddress;
					message.dstIP = link.router2.simulatedIPAddress;
					message.neighborID = link.router2.simulatedIPAddress;
					message.weight = link.weight;

					//increment router's LSA sequence number
					//System.out.println(lsd._store.get(rd.simulatedIPAddress).lsaSeqNumber);
					lsd._store.get(rd.simulatedIPAddress).lsaSeqNumber++;
					//System.out.println("Increased seq # : " + lsd._store.get(rd.simulatedIPAddress).lsaSeqNumber);

					//attach router's LSA to message
					message.lsaArray = new Vector<LSA>();
					message.lsaArray.add(lsd._store.get(rd.simulatedIPAddress));
					
					//create socket to connect to server
					Socket toServer = new Socket(link.router2.processIPAddress, link.router2.processPortNumber);

					// write to neighbor
					ObjectOutputStream out = new ObjectOutputStream(toServer.getOutputStream());
					out.writeObject(message);

					out.close();
					toServer.close();

				}catch(IOException e) {
					//e.printStackTrace();
				}

			}
		}
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

			//	System.out.println("I am here.\n");

		boolean attached = false;


		System.out.println("Started " + started);
		
		if(!started) {
			System.out.println("Router not started yet.\n");
			return;
		}

        //first attach
		processAttach(processIP, processPort, simulatedIP, weight);

		int index = 0;

		for(Link l : this.ports){
			if(l.router2.simulatedIPAddress != null){
				if (l.router2.simulatedIPAddress == simulatedIP){
					attached = true;
					break;
				}
			}
			index++;

		}

		if(attached == true){
			boolean linkSuccessfull = true;
             //connect and lsd sync
			 Link link = this.ports[index];

			 //TWO_WAY already established on link
			if(link.router2.status != null && link.router2.status.equals(RouterStatus.TWO_WAY)) return;

			//otherwise
			try {

				//create new SOSPFPacket to send HELLO to neighbor
				SOSPFPacket message = new SOSPFPacket();
				message.srcProcessIP = rd.processIPAddress;
				message.srcProcessPort = rd.processPortNumber;
				message.sospfType = 0;
				message.routerID = rd.simulatedIPAddress;
				message.srcIP = rd.simulatedIPAddress;
				message.dstIP = link.router2.simulatedIPAddress;
				message.neighborID = link.router2.simulatedIPAddress;
				message.weight = link.weight;

				//create socket to connect to server
				Socket server = new Socket(link.router2.processIPAddress, link.router2.processPortNumber);

				// write to neighbor and receive reply
				ObjectOutputStream out = new ObjectOutputStream(server.getOutputStream());
				out.writeObject(message);
				ObjectInputStream in = new ObjectInputStream(server.getInputStream());
				SOSPFPacket reply = (SOSPFPacket) in.readObject();

				//if sospfType of reply is 0
				if (reply.sospfType == 0) {				
					System.out.println("received Hello from " + reply.srcIP + ";\n");
					link.router2.status = RouterStatus.TWO_WAY;
					// send message again
					out.writeObject(message);
					System.out.println("set " + link.router2.simulatedIPAddress + " state to " + link.router2.status + ";\n");
				}
				if (reply.sospfType == -1) {
					System.out.println("Failed to link. No available empty ports.\n");
					this.ports[index] = null;	
					linkSuccessfull = false;				
					//continue;
				}

				in.close();
				out.close();
				server.close();

		    }catch (ClassNotFoundException e) {
				e.printStackTrace();
			}catch (  IOException  e ){
				//e.printStackTrace();
				System.out.println("Router " + link.router2.simulatedIPAddress + " does not exist.");
				this.ports[index] = null;
			}

			if(linkSuccessfull == true) {

				try{
			
			    	//if two way connection established, add link to LSA instance of router
			    	if (link != null && link.router2.status.equals(RouterStatus.TWO_WAY)) {
					   LinkDescription ld = new LinkDescription();
					   ld.linkID = link.router2.simulatedIPAddress;
					   ld.portNum = link.router2.processPortNumber;
					   ld.tosMetrics = link.weight;
					   lsd._store.get(this.rd.simulatedIPAddress).links.add(ld);	
				       //	System.out.println("Storing in own");
				    }
			    }catch(Exception e) {
			          e.printStackTrace();
		        }
				
				LSAUPDATEMessage();
			}



		
		}



	}

	/**
	 * output the neighbors of the routers
	 */
	private void processNeighbors() {

		for (int i = 0; i < this.ports.length; i++){
			if (this.ports[i] != null) {
				System.out.println("IP Address of neighbor " + (i+1) + ": " + this.ports[i].router2.simulatedIPAddress);
			}
		}
	}

	/**
	 * disconnect with all neighbors and quit the program
	 */
	private void processQuit() {

		for(int i = 0; i < 4; i++){
			if(this.ports[i] != null){
				processDisconnect((short)i);

			}
		}

		System.exit(0);

	}

	public void terminal() {
		try {
			InputStreamReader isReader = new InputStreamReader(System.in);
			BufferedReader br = new BufferedReader(isReader);
			System.out.print(">> ");
			String command = br.readLine();
			while (true) {
				if (command.startsWith("detect ")) {
					String[] cmdLine = command.split(" ");
					processDetect(cmdLine[1]);
				} else if (command.startsWith("disconnect ")) {
					String[] cmdLine = command.split(" ");
					processDisconnect(Short.parseShort(cmdLine[1]));
				} else if (command.startsWith("quit")) {
					processQuit();
				} else if (command.startsWith("attach ")) {
					String[] cmdLine = command.split(" ");
					processAttach(cmdLine[1], Short.parseShort(cmdLine[2]),
							cmdLine[3], Short.parseShort(cmdLine[4]));
				} else if (command.equals("start")) {
					processStart();
				} else if (command.equals("connect ")) {
					String[] cmdLine = command.split(" ");
					processConnect(cmdLine[1], Short.parseShort(cmdLine[2]),
							cmdLine[3], Short.parseShort(cmdLine[4]));
				} else if (command.equals("neighbors")) {
					//output neighbors
					processNeighbors();
				} else {
					//invalid command
					break;
				}
				System.out.print(">> ");
				command = br.readLine();
			}
			isReader.close();
			br.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}


//creates server/router instance
class Server implements Runnable{

	//router attributes
	private ServerSocket server;
	private Link[] ports;
	private RouterDescription rd;
	private LinkStateDatabase lsd;

	//constructor (creates router instance)
	public Server(ServerSocket server, Link[] ports, RouterDescription rd, LinkStateDatabase lsd) {
		this.server = server;
		this.ports = ports;
		this.rd = rd;
		this.lsd = lsd;
	}	

	//the sever keeps listening for new client requests
	public void run() {		

			try {	
				
				while(true) {
					//accept connection request from client
					Socket client = server.accept();	
					//reply through client socket
					ObjectOutputStream out = new ObjectOutputStream(client.getOutputStream());
					//receive via client socket
					ObjectInputStream in = new ObjectInputStream(client.getInputStream());	
					//once a client connects, create channel between client and server for communication
					new Thread(new Channel(in, out)).start();
					//System.out.println("Opening channel.\n");
				}
				
			}catch (IOException e) {
				e.printStackTrace();
			}	
		
			//System.out.println("Server closed.\n");

	}

	//returns rd of client router
	private RouterDescription findSender(String simulatedIP) {
		for (Link link : this.ports) {
			if (link != null && link.router2.simulatedIPAddress.equals(simulatedIP)) {
				return link.router2;
			}
		}
		return null;
	}	

	//find empty port for new link
	private int findPort() {
		for (int i = 0; i < this.ports.length; i++) {
			if (this.ports[i] == null) {
				return i;
			}
		}
		//no empty port
		return -1;
	}

	private void removeNeighbor(String id){
		for(int i = 0; i <=3; i++){
			if(this.ports[i].router2.simulatedIPAddress.equals(id)){
				this.ports[i] = null;
					return;
			}
		}
	}
									
	//send LSAUPDATE message to all neighbors
	private void LSAUPDATEMessage(Vector<LSA> arr, String ip) {

		for (Link link : this.ports) {
		  if(link != null && link.router2.status.equals(RouterStatus.TWO_WAY) && !link.router2.simulatedIPAddress.equals(ip)){
				try {

					//create new SOSPFPacket to send LSAUPDATE to neighbor
					SOSPFPacket message = new SOSPFPacket();
					message.srcProcessIP = rd.processIPAddress;
					message.srcProcessPort = rd.processPortNumber;
					message.sospfType = 1;
					message.routerID = rd.simulatedIPAddress;
					message.srcIP = rd.simulatedIPAddress;
					message.dstIP = link.router2.simulatedIPAddress;
					message.neighborID = link.router2.simulatedIPAddress;
					message.weight = link.weight;

					
					
					if(arr == null) {
						message.lsaArray = new Vector<LSA>();
					}else message.lsaArray = arr;

					if(message.lsaArray.isEmpty()){
						return;
					}
					
					//create socket to connect to server
					Socket toServer = new Socket(link.router2.processIPAddress, link.router2.processPortNumber);

					// write to neighbor
					ObjectOutputStream out = new ObjectOutputStream(toServer.getOutputStream());
					out.writeObject(message);

					out.close();
					toServer.close();

				}catch(IOException e) {
					//e.printStackTrace();
				}

			}
		}
	}

	//provides abstraction and acts like a pipe for communicating
	class Channel implements Runnable{

		//sospfType
		private int hello = 0;
		private int lsaupdate = 1;
		//incoming and outgoing messages
		ObjectInputStream in = null;
		ObjectOutputStream out = null;

		//create channel instance between server and client
		public Channel(ObjectInputStream in, ObjectOutputStream out) {
			this.in = in;
			this.out = out;
		}

		//keeps receiving and sending messages
		public void run() {
			try {	
				while(true) {
					
				//	System.out.println("The channel is open.\n");
					//receive incoming packet
					SOSPFPacket incomingMsg = (SOSPFPacket) in.readObject();
					//find client router description
					RouterDescription client = findSender(incomingMsg.srcIP);

					//if client is not added as neighbor yet, add client to port
					if (client == null) {
						//find empty port
						int port = findPort();
						if (port != -1) {
							//create new client rd
							client = new RouterDescription();
							client.processIPAddress = incomingMsg.srcProcessIP;
							client.processPortNumber = incomingMsg.srcProcessPort;
							client.simulatedIPAddress = incomingMsg.srcIP;
							//add link to port
							ports[port] = new Link(rd, client, incomingMsg.weight);
						}else {
							System.out.println("No empty ports for new connection.\n");
							//return error
							SOSPFPacket reply = new SOSPFPacket();
							reply.sospfType = -1;
							out.writeObject(reply);
							//return;
						}
					}

					if(client != null){
							//if client is added as neighbor and receiving HELLO for the first time from client
					if (incomingMsg.sospfType == hello && client.status == null) {

						//init
						client.status = RouterStatus.INIT;
						System.out.println("received HELLO from " + incomingMsg.srcIP + ";\n");
						System.out.println("set " + client.simulatedIPAddress + " state to " + client.status + ";\n");

						//prepare response
						SOSPFPacket reply = new SOSPFPacket();
						reply.srcProcessIP = rd.processIPAddress;
						reply.srcProcessPort = rd.processPortNumber;
						reply.srcIP = rd.simulatedIPAddress;
						reply.dstIP = client.simulatedIPAddress;
						reply.sospfType = 0;
						reply.routerID = rd.simulatedIPAddress;
						reply.neighborID = client.simulatedIPAddress;
						for (Link l : ports) {
							if (l.router2 == client) {
								reply.weight = l.weight;
								break;
							}
						}
						//send HELLO back
						out.writeObject(reply);
						//return;

					}else if(incomingMsg.sospfType == hello && client.status == RouterStatus.INIT) { 
						//second HELLO, then establish two way connection
						client.status = RouterStatus.TWO_WAY;
						System.out.println("received HELLO from " + incomingMsg.srcIP + ";\n");
						System.out.println("set " + client.simulatedIPAddress + " state to " + client.status + ";\n");
						break;	

					}else if(incomingMsg.sospfType == lsaupdate) {

                    //----------------------------------------------------------------------------------------------------------------//
					//------------------ MESSAGE TYPE LSA UPDATE   MESSAGE TYPE LSA UPDATE   MESSAGE TYPE LSA UPDATE -----------------//					
                    //----------------------------------------------------------------------------------------------------------------//

						Vector<LSA> messageToForward = new Vector<LSA>();  //this will store all LSAs that need to be forwarded
						int flag = 0;

						for(LSA lsa : incomingMsg.lsaArray) {  

							boolean iAmStillPresent = true;
							//System.out.println("Receiving LSA from router : " + lsa.linkStateID + " sequence # : "  + lsa.lsaSeqNumber);

							//check if LSA is from a neighbor
							boolean isFromNeighbor = false;
							for(Link link : ports){
								if(link != null && link.router2.simulatedIPAddress.equals(lsa.linkStateID) && link.router2.simulatedIPAddress != rd.simulatedIPAddress){
									isFromNeighbor = true;
								//	System.out.println("Incoming LSA belongs to a neighbor.");
									break;
								}
							}

                            // -------------------------------------------------------------------------------------------------//
							//------------if this.router does not already have a LSA from this sender in it's LSD---------------//
                            // -------------------------------------------------------------------------------------------------//						
							if(lsd._store.get(lsa.linkStateID) == null) {

							//	System.out.println("Adding LSA to database for first time. LSA belongs to : " + lsa.linkStateID);
                                lsd._store.put(lsa.linkStateID, lsa);  
								messageToForward.add(lsa);  
								flag = 1; 

							}else{      //--------------------this.router already has an LSA from this sender-----------------					

								

								if(lsd._store.get(lsa.linkStateID).lsaSeqNumber == lsa.lsaSeqNumber){
								//	System.out.println("Same sequence number : " + lsa.lsaSeqNumber);
									continue;
								}
								if(lsd._store.get(lsa.linkStateID).lsaSeqNumber < lsa.lsaSeqNumber){
									lsd._store.replace(lsa.linkStateID, lsd._store.get(lsa.linkStateID), lsa);	
									messageToForward.add(lsa);	//forward LSA	
								}
							}

							
                            // ----------------------------------------------------------------------------------------------------------------
							// --------------------------------- If LSA is from NEIGHBOR ------------------------------------------------------
							// ----------------------------------------------------------------------------------------------------------------

							if(isFromNeighbor){

								//is link description of neighbor already in LSA of router?
								boolean alreadyPresent = false;	
																							
								for(LinkDescription ld : lsd._store.get(rd.simulatedIPAddress).links){  //get the LSA of this.router
							        
									// -------------------------------------------------------------------
								    //-----if ld of neighbor is already present in this.router's LSA------
							        // -------------------------------------------------------------------
									if(ld.linkID.equals(lsa.linkStateID)){   

										alreadyPresent = true;

							            // -------------------------------------------------------------------
										// ---Loop through lsa.links to find weight in the neighbor's LSA-----
										// -------------------------------------------------------------------
										for(LinkDescription ld2 : lsa.links){

											if(ld2.linkID.equals(rd.simulatedIPAddress)){

												//System.out.println("Found myself.");
												iAmStillPresent = true;

												if(ld.tosMetrics != (int)ld2.tosMetrics){

													ld.tosMetrics = (int)ld2.tosMetrics; //updating own LSA
													flag=1;  //i need to broadcast
													break;

												}else{
												}		
												break;
											}else{

											//	System.out.println("I could not find myself in neighbor's LSA links");
												iAmStillPresent = false;
											}
										}	

										if(lsa.links.isEmpty()) {
											iAmStillPresent = false;
											//System.out.println("Empty LSA");
										}						
									}
								}

								//-------------------------------------------------------------------------------------------------------------------							
								//------------------------------ If ld of neighbor is not already added in LSA of router ----------------------------
								//-------------------------------------------------------------------------------------------------------------------
								
								if(!alreadyPresent){

									int port = 0;
									int weight = 0;

									for(LinkDescription l : lsa.links){  
										if(l.linkID.equals(rd.simulatedIPAddress)){
											port = l.portNum;
											weight = l.tosMetrics;
										}
									}

									//add ld to own LSA
									LinkDescription ld = new LinkDescription();
									ld.linkID = lsa.linkStateID;
									ld.portNum = port;
									ld.tosMetrics = weight;
									lsd._store.get(rd.simulatedIPAddress).links.add(ld);
									flag = 1;      //flag = 1 means i have to broadcast my LSA to all neighbors

									//System.out.println("I have added ld to my LSA.");
									
								}	

 								//---------------------------------------------------------------------------------------------------------------------              
								//---------------------------------------------------------------------------------------------------------------------
                                


								if(iAmStillPresent == false && alreadyPresent == true) {

								//	System.out.println("If disconnect, remove neighbor.");

									int count = 0 ;

									for(Link link : ports){
										if(link.router2.simulatedIPAddress.equals(lsa.linkStateID)){
											ports[count] = null;
											
										}
										count++ ;
									}

									removeNeighbor(lsa.linkStateID);

									for(LinkDescription ld : lsd._store.get(rd.simulatedIPAddress).links){

									    //if already added in LSA, then update weight of link
									    if(ld.linkID.equals(lsa.linkStateID)){
											lsd._store.get(rd.simulatedIPAddress).links.remove(ld);
											break;
									    }
									}

									flag = 1;
								}


							} //isFromNeighbor ends here
						}      

                        //------------------------------------FOR LOOP of incomingMessage.lsaArray ends here-----------------------------------------------------

						if(flag==1){

							Vector<LSA> a = new Vector<LSA>();
						//	System.out.println(lsd._store.get(rd.simulatedIPAddress).lsaSeqNumber);
					        lsd._store.get(rd.simulatedIPAddress).lsaSeqNumber++;  //increment my LSA seq #
						//	System.out.println("Attaching my LSA. Increasing to : " + lsd._store.get(rd.simulatedIPAddress).lsaSeqNumber);


						    //attach router's LSA to message
						    a.add(lsd._store.get(rd.simulatedIPAddress));
							LSAUPDATEMessage(a, rd.simulatedIPAddress);
						}

						LSAUPDATEMessage(messageToForward,incomingMsg.srcIP);
						break;
					}

					}

				
				} //while(true)
			}catch(Exception e) {
				//e.printStackTrace();
			}
			
			//System.out.println("LSA UPDATE thread ended.\n");
		}
	}
}

























