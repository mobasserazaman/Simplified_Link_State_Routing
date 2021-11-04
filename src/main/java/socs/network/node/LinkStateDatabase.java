package socs.network.node;
import socs.network.message.LSA;
import socs.network.message.LinkDescription;
import java.util.HashSet;
import java.util.HashMap;

public class LinkStateDatabase {

  //linkID => LSAInstance
  HashMap<String, LSA> _store = new HashMap<String, LSA>();

  private RouterDescription rd = null;

  public LinkStateDatabase(RouterDescription routerDescription) {
    rd = routerDescription;
    LSA l = initLinkStateDatabase();
    _store.put(l.linkStateID, l);
  }

  /**
   * output the shortest path from this router to the destination with the given IP address
   */
  String getShortestPath(String destinationIP) {
    
    // this holds the shortest distance from src(router) to destinationIP
    HashMap<String, Integer> dist = new HashMap<String, Integer>();
    // this holds the name of the previous router on the shortest path
    HashMap<String, String> prev = new HashMap<String, String>();
    // notVisited nodes
    HashSet<String> notVisited = new HashSet<String>();

    if(!(_store.keySet().contains(destinationIP))){
      return "No path to " + destinationIP + "exists.\n";
    }

    //initialize all distances as MAX_VALUE
     for (String router : _store.keySet()) {
     // System.out.println("Router : "+ router + "\n");
      dist.put(router, Integer.MAX_VALUE);
      prev.put(router, null);
      notVisited.add(router);
    }

    // the distance of source vertex from itself is always 0
    dist.put(rd.simulatedIPAddress, 0);
    prev.put(rd.simulatedIPAddress, null);
  //  boolean found = false;
  while (!notVisited.isEmpty())
  {
    //Find shortest path
    int min = Integer.MAX_VALUE;  
    String minDistRouter = null;

    for (String router : notVisited) {
    //  System.out.println("NotVisited router : " + router);
      if (dist.get(router) < min) {
        min = dist.get(router);
        minDistRouter = router;
     //   System.out.println("Minimum distance router : " + minDistRouter + ", Dist : " + min);
      }
    }
    notVisited.remove(minDistRouter);

    if(minDistRouter == null){
      System.out.println("NULL.\n");
    } 


    // Update dist value of the adjacent vertices of the picked vertex
    for (LinkDescription ld : _store.get(minDistRouter).links) {
        if (notVisited.contains(ld.linkID)) {
     //     System.out.println(ld.linkID);
        int sum = dist.get(minDistRouter) + ld.tosMetrics;
          if (sum < dist.get(ld.linkID)) {
            //update distance           
             dist.put(ld.linkID, sum);
             prev.put(ld.linkID, minDistRouter);   
          }
      }
    }
  }

    String current = destinationIP;
    String target = destinationIP;

    while (prev.get(target) != null) {
      //previous node
      String previous = prev.get(target);
      int length = 0;
      for (LinkDescription ld : _store.get(previous).links){
        if (ld.linkID.equals(target)) {
          length = ld.tosMetrics;
        }
      }
      current = previous + " ->(" + length + ") " + current;
      target = previous;
    }

  //  System.out.println(current);

    return current;

  }

  //initialize the linkstate database by adding an entry about the router itself
  private LSA initLinkStateDatabase() {
    LSA lsa = new LSA();
    lsa.linkStateID = rd.simulatedIPAddress;
    lsa.lsaSeqNumber = Integer.MIN_VALUE;
    LinkDescription ld = new LinkDescription();
    ld.linkID = rd.simulatedIPAddress;
    ld.portNum = -1;
    ld.tosMetrics = 0;
    lsa.links.add(ld);
    return lsa;
  }


  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (LSA lsa: _store.values()) {
      sb.append(lsa.linkStateID).append("(" + lsa.lsaSeqNumber + ")").append(":\t");
      for (LinkDescription ld : lsa.links) {
        sb.append(ld.linkID).append(",").append(ld.portNum).append(",").
                append(ld.tosMetrics).append("\t");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

}
