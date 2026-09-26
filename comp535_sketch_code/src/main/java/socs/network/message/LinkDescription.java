package socs.network.message;

import java.io.Serializable;

public class LinkDescription implements Serializable {
  public String linkID;
  public int portNum;
  public int tosMetrics;
  public int weight; //link weight/cost for shortest path calculation

  public String toString() {
    return linkID + ","  + portNum + "," + weight;
  }
}
