package demos.SMTP.TranslatedSMTP;

/**
 * Generated by StMungo
 * Thu Apr 23 18:59:34 BST 2020
 */
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.io.BufferedReader;

import java.lang.String;

@Typestate("CProtocol")
public class CRole{
    private BufferedReader socketSIn = null;
    private PrintWriter socketSOut = null;
    public CRole() {
        // Connect to the other participants in the protocol
        try {
            // Create the sockets
            Socket socketS = new Socket("localhost", 20000);
            socketSIn = new BufferedReader(new InputStreamReader(socketS.getInputStream()));
            socketSOut = new PrintWriter(socketS.getOutputStream(), true);
        } catch(UnknownHostException e) {
            System.out.println("Unable to connect to the remote host");
            System.exit(-1);
        } catch (IOException e) {
            System.out.println("Input/output error");
            System.exit(-1);
        }
    }

    public String receive_220StringFromS() {
        String line = "";
        try {
            line = this.socketSIn.readLine();
        } catch(IOException e) {
            System.out.println("Input/Outpur error. " + e.getMessage());
            System.exit(-1);
        }
        return line;
    }

    public void send_EHLOToS() {
        this.socketSOut.println("EHLO");
    }

    public void send_QUITToS() {
        this.socketSOut.println("QUIT");
    }

    public void send_ehloStringToS(String payload0) {
        this.socketSOut.println(payload0);
    }

    public CChoice1 receive_CChoice1LabelFromS() {
        String stringLabelCChoice1 = "";
        try {
            stringLabelCChoice1 = this.socketSIn.readLine();
        } catch(IOException e) {
            System.out.println("Input/Outpur error, unable to get label. " + e.getMessage());
            System.exit(-1);
        }
        switch(stringLabelCChoice1) {
            case "_250DASH":
                return CChoice1._250DASH;
            case "_250":
            default:
                return CChoice1._250;
        }
    }

    public String receive_250dashStringFromS() {
        String line = "";
        try {
            line = this.socketSIn.readLine();
        } catch(IOException e) {
            System.out.println("Input/Outpur error. " + e.getMessage());
            System.exit(-1);
        }
        return line;
    }

    public String receive_250StringFromS() {
        String line = "";
        try {
            line = this.socketSIn.readLine();
        } catch(IOException e) {
            System.out.println("Input/Outpur error. " + e.getMessage());
            System.exit(-1);
        }
        return line;
    }

    public void send_STARTTLSToS() {
        this.socketSOut.println("STARTTLS");
    }

    public void send_starttlsStringToS(String payload0) {
        this.socketSOut.println(payload0);
    }

    public void send_AUTHToS() {
        this.socketSOut.println("AUTH");
    }

    public void send_authStringToS(String payload0) {
        this.socketSOut.println(payload0);
    }

    public CChoice2 receive_CChoice2LabelFromS() {
        String stringLabelCChoice2 = "";
        try {
            stringLabelCChoice2 = this.socketSIn.readLine();
        } catch(IOException e) {
            System.out.println("Input/Outpur error, unable to get label. " + e.getMessage());
            System.exit(-1);
        }
        switch(stringLabelCChoice2) {
            case "_235":
                return CChoice2._235;
            case "_535":
            default:
                return CChoice2._535;
        }
    }

    public String receive_235StringFromS() {
        String line = "";
        try {
            line = this.socketSIn.readLine();
        } catch(IOException e) {
            System.out.println("Input/Outpur error. " + e.getMessage());
            System.exit(-1);
        }
        return line;
    }

    public void send_MAILToS() {
        this.socketSOut.println("MAIL");
    }

    public void send_mailStringToS(String payload0) {
        this.socketSOut.println(payload0);
    }

    public CChoice3 receive_CChoice3LabelFromS() {
        String stringLabelCChoice3 = "";
        try {
            stringLabelCChoice3 = this.socketSIn.readLine();
        } catch(IOException e) {
            System.out.println("Input/Outpur error, unable to get label. " + e.getMessage());
            System.exit(-1);
        }
        switch(stringLabelCChoice3) {
            case "_501":
                return CChoice3._501;
            case "_250":
            default:
                return CChoice3._250;
        }
    }

    public String receive_501StringFromS() {
        String line = "";
        try {
            line = this.socketSIn.readLine();
        } catch(IOException e) {
            System.out.println("Input/Outpur error. " + e.getMessage());
            System.exit(-1);
        }
        return line;
    }

    public void send_RCPTToS() {
        this.socketSOut.println("RCPT");
    }

    public void send_DATAToS() {
        this.socketSOut.println("DATA");
    }

    public void send_rcptStringToS(String payload0) {
        this.socketSOut.println(payload0);
    }

    public CChoice4 receive_CChoice4LabelFromS() {
        String stringLabelCChoice4 = "";
        try {
            stringLabelCChoice4 = this.socketSIn.readLine();
        } catch(IOException e) {
            System.out.println("Input/Outpur error, unable to get label. " + e.getMessage());
            System.exit(-1);
        }
        switch(stringLabelCChoice4) {
            case "_250":
            default:
                return CChoice4._250;
        }
    }

    public void send_dataStringToS(String payload0) {
        this.socketSOut.println(payload0);
    }

    public String receive_354StringFromS() {
        String line = "";
        try {
            line = this.socketSIn.readLine();
        } catch(IOException e) {
            System.out.println("Input/Outpur error. " + e.getMessage());
            System.exit(-1);
        }
        return line;
    }

    public void send_DATALINEToS() {
        this.socketSOut.println("DATALINE");
    }

    public void send_SUBJECTToS() {
        this.socketSOut.println("SUBJECT");
    }

    public void send_ATADToS() {
        this.socketSOut.println("ATAD");
    }

    public void send_datalineStringToS(String payload0) {
        this.socketSOut.println(payload0);
    }

    public void send_subjectStringToS(String payload0) {
        this.socketSOut.println(payload0);
    }

    public void send_atadStringToS(String payload0) {
        this.socketSOut.println(payload0);
    }

    public void send_quitStringToS(String payload0) {
        this.socketSOut.println(payload0);
    }

    public String receive_535StringFromS() {
        String line = "";
        try {
            line = this.socketSIn.readLine();
        } catch(IOException e) {
            System.out.println("Input/Outpur error. " + e.getMessage());
            System.exit(-1);
        }
        return line;
    }

}