# Knot

is a peer-to-peer network that (will) be used (by me) to deploy my projects.

Currently it is just a very basic implementation.

## What it can do!

Start a node on a server.

Start a second node on a server and connect them.

The connect via the cli and you can:
1. upload files
2. open a tunnel. Which will open a TCP server on the target and forwards all traffic to your local machine.

## What it is going to do!

1. Upload files
2. Open a tunnel. Which will open a TCP server on the target and forwards all traffic to your local machine.
3. Open a reverse tunnel. Which will open a TCP server on your local machine and forwards all traffic to the target.
4. Deploy a project with a simple command
5. Move projects between nodes
6. Use Ingress and Egress
7. Injects itself in your local DNS resolver to resolve ALL traffic from `*.cluster` direclty in the right container/deployment
8. Forwards all traffic from a public node to a private peer (e.g. behind a NAT/Router or your local machine for development)

That is the plan.

## Want to try it out?

Currently, it is just a very basic implementation that barely works.

If you want to try it out, keep reading. But I warned you.

Go to the newest release and download:
1. `knot-cli-X.X-SNAPSHOT.jar`
2. `knot-node-X.X-SNAPSHOT.jar`

You need also download the [knot-pki.sh](utils/knot-pki.sh) (which was vibe-coded, but works).
It allows you to generate the certificates for the peers.

1. Run it.
2. Select `Create a new Certificate Authority` and press enter.
3. Follow the instructions, there are really simple, and the program does nothing without your consent.
4. After generating a new Root certificate authority, you need to create a new certificate for each peer.
5. Select `Create a new Peer Identity` and press enter.
6. Follow the instructions again.
7. If you want to follow the next steps, please do step `5` at least three times.
8. Now you have a directory tree like this:
```
knot-pki/
    ca.crt
    ca.key
    ca.srl
    peer-91b600e35e32d940a1cd0e63e8f7361f.crt
    peer-91b600e35e32d940a1cd0e63e8f7361f.id
    peer-91b600e35e32d940a1cd0e63e8f7361f.key
    peer-91b600e35e32d940a1cd0e63e8f7361f.p12
    peer-4858dce95d13bd801c1e1dad42847e38.crt
    peer-4858dce95d13bd801c1e1dad42847e38.id
    peer-4858dce95d13bd801c1e1dad42847e38.key
    peer-4858dce95d13bd801c1e1dad42847e38.p12
    peer-ddb2a89e4aa458860691de4957321bca.crt
    peer-ddb2a89e4aa458860691de4957321bca.id
    peer-ddb2a89e4aa458860691de4957321bca.key
    peer-ddb2a89e4aa458860691de4957321bca.p12
```

A little explanation:
1. `ca.crt` contains the Root certificate.
2. `ca.key` contains the private key of the root certificate authority (I hope you protected it with a password).
3. `ca.srl` actually no idea why this is needed, but it is, so just leave it as it is.
4. `peer-*.id` contains the peer's identity.
5. `peer-*.crt` contains the peer's certificate.
6. `peer-*.key` contains the peer's private key.
7. `peer-*.p12` contains the peer's certificate and private key in a PKCS#12 file.

Create a new directory (e.g. `node-a`) and copy the `ca.crt` and the `knot-node-X.X-SNAPSHOT.jar` in it.
Also copy one `peer-*.id`, `peer-*.p12` but rename them to `peer.id` and `peer.p12`.
Then at last create `peer.p12.password`, it should contain the password of the PKCS#12 file. (Which you entered in the previous step: `Choose a password for the .p12 keystore.`).

Do the same the second node (e.g. `node-b`), but use other `peer-*.id`, `peer-*.p12`.

Now start the first node (e.g. `node-a`) by running `java -jar knot-node-X.X-SNAPSHOT.jar`.

Now start the second node (e.g. `node-b`) by running `java -jar knot-node-X.X-SNAPSHOT.jar --port=5002 --bootstrap.node=localhost:5001`.
The default port is `5001`, which is already used by the first node.
`bootstrap.node` is used to tell the second node how to connect to the first node.

When a node is connected to another node, it will automatically save the peer's identity and its address to `known.peers`.
So after the first start you should remove the `bootstrap.node` from the command line arguments.

Now to the CLI.

Run it with `java -jar knot-node-X.X-SNAPSHOT.jar`.

It will print all required arguments.

But for simplification, you can read them here:

| Name               | Shortform | Descreption                           | Requiered | Default |
|--------------------|-----------|---------------------------------------|-----------|---------|
| host               | -         | The host to connecto to               | true      | -       |
| port               | -         | The port to connecto to               | true      | -       |
| ca                 | ca        | The path to the ca.crt file           | true      | -       |
| key-store-file     | key       | The path to the .p12 file             | true      | -       |
| key-store-password | pass      | The password of the .p12 file         | true      | -       |
| local-peer-id      | id        | The local peer id (from the .id file) | true      | -       |
| output-dir         | o         | The output dir of receiving files     | false     | ./files |


After connecting to the network via the provided host and port, the cli is ready to send commands.

Also, there are a bunch of debug logger lines (told ya, it is not pretty).

There is a help command, which should print all available commands. (But it is not implemented yet, so you need to read the following documentation.)

1. `stop`, `exit` - does what it says
2. `connect` - takes a host and port (separated with spaces (like all other commands)) and connects to the provided host and port
3. `upload` - takes a target id and a file path and uploads the file to the target peer
4. `tunnel`, `forward`
   - `start` - takes a target id and a destination host and port and optionally a host and port to bind to (you should provide those, because if not provided, the defaults are `0.0.0.0` and `0` (`0` means: let the system decide))
   - `stop` - stops all tunnels
5. `ls`, `list` - lists all connected peers and if the network topology is known (which does take some time), it lists also the peer id of known peers
6. `help` - throws a `NotImplementedError` (yeah, I will change that)

There is currently a unknown bug, which **swallows** packets. But I could not find the reason yet. So it may take one or more software restarts to fix it.

So have fun! If you have any questions, feel free to ask!

(But remember, only a simple implementation)