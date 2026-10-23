# Bouncy Castle (Argon2id, AES-GCM) ships JCA provider and LDAP code that references JDK classes Android lacks.
# Cucuto only calls the lightweight API (org.bouncycastle.crypto), so those references are never reached.
-dontwarn javax.naming.**
