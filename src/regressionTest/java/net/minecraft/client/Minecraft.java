package net.minecraft.client;
public class Minecraft {
 private static final Minecraft INSTANCE = new Minecraft();
 private final ThreadLocal<Boolean> clientTask = ThreadLocal.withInitial(() -> false);
 public volatile boolean delayTasks;
 public final java.util.Queue<Runnable> queuedTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
 public Object player;
 public static Minecraft getInstance() { return INSTANCE; }
 public Object getConnection() { return null; }
 public boolean isSameThread() { return clientTask.get(); }
 public void execute(Runnable r) {
  if (delayTasks) { queuedTasks.add(r); return; }
  boolean previous = clientTask.get();
  clientTask.set(true);
  try { r.run(); } finally { clientTask.set(previous); }
 }
 public void runQueuedTasks() {
  Runnable task;
  while ((task = queuedTasks.poll()) != null) execute(task);
 }
 public User getUser() { return new User(); }
 public static class User { public String getName() { return "Alice"; } }
}
