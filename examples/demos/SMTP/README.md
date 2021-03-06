SMTP (Simple Mail Transfer Protocol) is an Internet standard electronic mail transfer protocol, 
which typically runs over a TCP (Transmission Control Protocol) connection. We consider the version 
defined in RFC 5321. An SMTP interaction consists of an exchange of text-based commands between the client and the server.

The TranslatedSMTP contains the files generated by StMungo for both the client role, C and the server role, S. 

For the client role the following files are generated:
1. CProtocol.protocol, captures the interactions local to the C role
as a typestate specification.
2. CRole.java is a class that implements CProtocol by communication over Java sockets. This is an API that can be used to implement the SMTP client endpoint.
3. CMain.java is a skeletal implementation of the SMTP client endpoint. It runs as a Java process and provides a main() method that uses CRole to communicate with the SMTP server.

The FinalSMTP folder contains the 
SMTP client further implemented to communicate with the gmail server.
