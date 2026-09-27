package net.minecraft.client;
public class Minecraft {
 private static final Minecraft INSTANCE = new Minecraft();
 public Object player;
 public static Minecraft getInstance() { return INSTANCE; }
 public Object getConnection() { return null; }
 public boolean isSameThread() { return true; }
 public void execute(Runnable r) { r.run(); }
 public User getUser() { return new User(); }
 public static class User { public String getName() { return "Alice"; } }
}
