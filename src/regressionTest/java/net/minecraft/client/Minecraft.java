package net.minecraft.client;
public class Minecraft {
 private static final Minecraft INSTANCE = new Minecraft();
 private final ThreadLocal<Boolean> clientTask = ThreadLocal.withInitial(() -> false);
 public volatile boolean delayTasks;
 public final java.util.Queue<Runnable> queuedTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
 public Object player;
 /**
  * Provides the get instance fixture operation used by the minecraft regression scenarios.
  *
  * @return the result described above
  */
 public static Minecraft getInstance() { return INSTANCE; }
 /**
  * Provides the get connection fixture operation used by the minecraft regression scenarios.
  *
  * @return the result described above
  */
 public Object getConnection() { return null; }
 /**
  * Provides the is same thread fixture operation used by the minecraft regression scenarios.
  *
  * @return whether the condition or operation described above succeeds
  */
 public boolean isSameThread() { return clientTask.get(); }
 /**
  * Provides the execute fixture operation used by the minecraft regression scenarios.
  *
  * @param r the r supplied to this operation
  */
 public void execute(Runnable r) {
  if (delayTasks) { queuedTasks.add(r); return; }
  boolean previous = clientTask.get();
  clientTask.set(true);
  try { r.run(); } finally { clientTask.set(previous); }
 }
 /**
  * Provides the run queued tasks fixture operation used by the minecraft regression scenarios.
  */
 public void runQueuedTasks() {
  Runnable task;
  while ((task = queuedTasks.poll()) != null) execute(task);
 }
 /**
  * Provides the get user fixture operation used by the minecraft regression scenarios.
  *
  * @return the result described above
  */
 public User getUser() { return new User(); }
 public static class User {
        /**
         * Provides the get name fixture operation used by the minecraft regression scenarios.
         *
         * @return the result described above
         */
        public String getName() { return "Alice"; } }
}
