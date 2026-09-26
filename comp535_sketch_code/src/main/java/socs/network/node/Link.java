package socs.network.node;

public class Link {

  RouterDescription router1;
  RouterDescription router2;
  int weight;  // <- new field record for link's weight

  public Link(RouterDescription r1, RouterDescription r2, int w) {
    router1 = r1;
    router2 = r2;
    weight = w;
  }
}
