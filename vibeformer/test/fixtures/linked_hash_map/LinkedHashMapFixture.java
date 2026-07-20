package fixture.linkedhashmap;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

public final class LinkedHashMapFixture {
  private LinkedHashMapFixture() {}

  private static String entries(Map<String, String> map) {
    var result = new StringBuilder();
    for (var entry : map.entrySet()) {
      if (result.length() > 0) result.append(',');
      result.append(entry.getKey()).append('=');
      result.append(entry.getValue() == null ? "<null>" : entry.getValue());
    }
    return result.toString();
  }

  private static String insertionOrder() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("a", "1");
    map.put("b", "2");
    map.put("c", "3");
    map.put("b", "B");
    map.remove("a");
    map.put("a", "A");
    return entries(map);
  }

  private static String accessOrder() {
    Map<String, String> map = new LinkedHashMap<>(8, 0.75f, true);
    map.put("a", "1");
    map.put("b", "2");
    map.put("c", "3");
    map.get("a");
    map.getOrDefault("b", "missing");
    map.putIfAbsent("c", "ignored");
    map.put("c", "C");
    map.remove("b");
    map.put("b", "B");
    return entries(map);
  }

  private static String bulkUpdates() {
    Map<String, String> updates = new LinkedHashMap<>();
    updates.put("b", "B");
    updates.put("d", "D");
    updates.put("a", "A");

    Map<String, String> insertion = new LinkedHashMap<>();
    insertion.put("a", "1");
    insertion.put("b", "2");
    insertion.put("c", "3");
    insertion.putAll(updates);

    Map<String, String> access = new LinkedHashMap<>(8, 0.75f, true);
    access.put("a", "1");
    access.put("b", "2");
    access.put("c", "3");
    access.putAll(updates);
    return entries(insertion) + "/" + entries(access);
  }

  private static String nullSemantics() {
    var genericFactories = new int[] {0};
    Map<String, String> generic = new HashMap<>();
    generic.put("n", null);
    generic.computeIfAbsent(
        "n",
        key -> {
          genericFactories[0]++;
          return null;
        });
    generic.computeIfAbsent(
        "missing",
        key -> {
          genericFactories[0]++;
          return null;
        });
    generic.computeIfAbsent(
        "n",
        key -> {
          genericFactories[0]++;
          return "N";
        });
    generic.put("p", null);
    generic.putIfAbsent("p", "P");

    var linkedFactories = new int[] {0};
    Map<String, String> linked = new LinkedHashMap<>(8, 0.75f, true);
    linked.put("a", "A");
    linked.put("n", null);
    linked.put("b", "B");
    linked.computeIfAbsent(
        "n",
        key -> {
          linkedFactories[0]++;
          return null;
        });
    linked.computeIfAbsent(
        "missing",
        key -> {
          linkedFactories[0]++;
          return null;
        });
    linked.computeIfAbsent(
        "n",
        key -> {
          linkedFactories[0]++;
          return "N";
        });
    linked.put("x", null);
    linked.putIfAbsent("x", "X");

    return "genericFactories="
        + genericFactories[0]
        + ",genericMissing="
        + generic.containsKey("missing")
        + ",genericN="
        + generic.get("n")
        + ",genericP="
        + generic.get("p")
        + ",linkedFactories="
        + linkedFactories[0]
        + ",linked="
        + entries(linked);
  }

  @SuppressWarnings("serial")
  private static final class TimingMap extends LinkedHashMap<String, String> {
    private int checks;

    private TimingMap() {
      super(8, 0.75f, false);
    }

    @Override
    protected boolean removeEldestEntry(Entry<String, String> eldest) {
      checks++;
      return size() > 2;
    }
  }

  private static String evictionTiming() {
    var map = new TimingMap();
    map.put("a", "1");
    map.put("b", "2");
    map.put("a", "A");
    map.computeIfAbsent("a", key -> "ignored");
    map.putIfAbsent("b", "ignored");
    Map<String, String> updates = new LinkedHashMap<>();
    updates.put("a", "AA");
    updates.put("c", "C");
    map.putAll(updates);
    return "checks=" + map.checks + ",entries=" + entries(map);
  }

  private static final class FakeHttpClient {
    private final String name;
    private boolean disposed;

    private FakeHttpClient(String name) {
      this.name = name;
    }

    private void close() {
      disposed = true;
    }
  }

  private static final class ExecutorSpiCacheObservation {
    private static final int MAX_HTTP_CLIENTS = 3;
    private final Map<String, FakeHttpClient> httpClients;

    @SuppressWarnings("serial")
    private ExecutorSpiCacheObservation() {
      var map =
          new LinkedHashMap<String, FakeHttpClient>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Entry<String, FakeHttpClient> eldest) {
              if (size() <= MAX_HTTP_CLIENTS) return false;
              eldest.getValue().close();
              return true;
            }
          };
      httpClients = Collections.synchronizedMap(map);
    }

    private FakeHttpClient getOrCreateHttpClient(String key) {
      return httpClients.computeIfAbsent(key, value -> new FakeHttpClient(value));
    }

    private String order() {
      var result = new StringBuilder();
      for (var client : httpClients.values()) {
        if (result.length() > 0) result.append(',');
        result.append(client.name);
      }
      return result.toString();
    }
  }

  private static String executorSpiCache() {
    var cache = new ExecutorSpiCacheObservation();
    var a = cache.getOrCreateHttpClient("A");
    var b = cache.getOrCreateHttpClient("B");
    cache.getOrCreateHttpClient("C");
    var hit = cache.getOrCreateHttpClient("A");
    cache.getOrCreateHttpClient("D");
    return "same="
        + (a == hit)
        + ",order="
        + cache.order()
        + ",disposed="
        + b.disposed
        + ",evicted="
        + !cache.httpClients.containsKey("B");
  }

  public static String observeAll() {
    return insertionOrder()
        + "|"
        + accessOrder()
        + "|"
        + bulkUpdates()
        + "|"
        + nullSemantics()
        + "|"
        + evictionTiming()
        + "|"
        + executorSpiCache();
  }

  public static void main(String[] args) {
    System.out.println(observeAll());
  }
}
