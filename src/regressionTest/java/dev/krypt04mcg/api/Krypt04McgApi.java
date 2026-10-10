package dev.krypt04mcg.api;
public class Krypt04McgApi {
 public static byte[] last;
 /**
  * Provides the send fixture operation used by the krypt04 mcg api regression scenarios.
  *
  * @param p the p supplied to this operation
  * @param c the c supplied to this operation
  * @param b the b supplied to this operation
  */
 public static void send(String p,String c,byte[] b) {last=b;}
 public static volatile KryptSocket lastSocket;
 /**
  * Provides the connect fixture operation used by the krypt04 mcg api regression scenarios.
  *
  * @param p the p supplied to this operation
  * @param c the c supplied to this operation
  * @return the result described above
  */
 public static KryptSocket connect(String p,String c) {
  if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Connect on client thread");
  return lastSocket = new KryptSocket();
 }
}
