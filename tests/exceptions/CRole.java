import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import mungo.lib.Typestate;
import org.checkerframework.checker.mungo.lib.MungoNullable;

@Typestate("CProtocol")
public class CRole {
  private @MungoNullable BufferedReader socketSIn = null;
  private @MungoNullable PrintWriter socketSOut = null;

  public CRole() {
    InetAddress addr;
    @MungoNullable Socket socket = null;

    try {
      addr = InetAddress.getByName("www.google.co.uk");
      // :: warning: (socket: Null)
      socket = new Socket(addr, 80);
    } catch (IOException e) {
      System.out.println("Unable to listen on ports");
      System.exit(-1);
    }

    try {
      // :: warning: (socketSIn: NoProtocol | Null)
      // :: warning: (socket: NoProtocol | Null)
      // :: error: (Cannot call getInputStream on null)
      socketSIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
      // :: warning: (socketSOut: NoProtocol | Null)
      socketSOut = new PrintWriter(socket.getOutputStream(), true);
    } catch (IOException e) {
      System.out.println("Read failed");
      System.exit(-1);
    }
  }

  public void send(String msg) {
    // :: error: (Cannot call print on null)
    this.socketSOut.print(msg);
  }

  public int receive() throws IOException {
    // :: error: (Cannot call read on null)
    return this.socketSIn.read();
  }

  public void close() {

  }

}
