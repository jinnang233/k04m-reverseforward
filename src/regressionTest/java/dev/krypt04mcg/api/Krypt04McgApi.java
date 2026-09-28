package dev.krypt04mcg.api;
public class Krypt04McgApi {
 public static byte[] last;
 public static DataTransfer send(String p,String c,byte[] b) {last=b; return new DataTransfer();}
 public static volatile KryptSocket lastSocket;
 public static KryptSocket connect(String p,String c) {
  if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Connect on client thread");
  return lastSocket = new KryptSocket();
 }
}
