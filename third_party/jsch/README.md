# JSch (mwiede fork), for SFTP

- What: the SSH library behind the Files tab's SFTP locations
  (src/org/pda/launcher/remote/SftpFs.java).
- Version: 2.28.7, `com.github.mwiede:jsch:2.28.7`, released 2026-08-20.
- From: https://repo1.maven.org/maven2/com/github/mwiede/jsch/2.28.7/jsch-2.28.7-sources.jar
  (sha1 04f90daef361f60fefee6335f4b2cfb9151cf8c6, checked against Maven Central's).
- Licence: BSD-style (LICENSE.txt); the bundled JZlib and jBCrypt are BSD/ISC
  too (LICENSE.JZlib.txt, LICENSE.jBCrypt.txt). Allowed by AGENTS.md rule 2.
- Built from source by build.sh with the app; no Gradle, no jar.

Left out (they need libraries the app does not carry; optional in JSch):
`com/jcraft/jsch/bc/` (Bouncy Castle), `jgss/` (GSS-API), the SLF4J and Log4j
loggers, the JNA/junixsocket agent connectors (`SSHAgentConnector`,
`PageantConnector`, `JUnixSocketFactory`, ...) and the multi-release classes
for Java 11+ (`META-INF/versions`).

So, on Android: Ed25519 keys and curve25519 key exchange are not available
(they come from Java 15+ or Bouncy Castle); RSA and ECDSA keys, passwords,
ECDH (nistp256/384/521) and Diffie-Hellman exchanges, AES-CTR/GCM are.

To update: fetch the new sources jar (a release at least two weeks old),
check its sha1, drop the same files, replace src/, run the JVM test of
SftpFs, then build.
