import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Miner {

    /**
     * in terinal write this 
     * javac Miner.java
     * then to run
     * java -Xms512m -Xmx512m Miner <prevHash> <pseudonym> <startDifficulty> <targetDifficulty> [threads I put 8]
     */

    // File I/O 
    static void recordBlock(String pseudonym, String blockLine) throws IOException {
        Path p = Paths.get("chain.txt"); // File to store the blockchain
        if (Files.notExists(p)) {
            Files.write(p, Arrays.asList(pseudonym), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        }
        Files.write(p, Arrays.asList(blockLine), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    //  Pseudonym rules 
    static boolean pseudonymIsValid(String pseudo) {
        if (pseudo == null || pseudo.length() == 0 || pseudo.length() > 30) return false;
        for (char ch : pseudo.toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '_') continue;
            return false;
        }
        return true;
    }

    // Hashing primitives 
    static final ThreadLocal<MessageDigest> MD = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new RuntimeException(e); }
    });

    static int leadingZeroBits(byte[] h) {
        int bits = 0;
        for (byte value : h) {
            int v = value & 0xFF;
            if (v == 0) { bits += 8; continue; }
            bits += Integer.numberOfLeadingZeros(v) - 24;
            break;
        }
        return bits;
    }
    // Hex conversion
    static String toHex(byte[] h) {
        char[] HEX = "0123456789abcdef".toCharArray();
        char[] out = new char[h.length * 2];
        int j = 0;
        for (byte b : h) {
            int v = b & 0xFF;
            out[j++] = HEX[v >>> 4];
            out[j++] = HEX[v & 0x0F];
        }
        return new String(out);
    }
    // Long to ASCII conversion
    static int writeLongAscii(long x, byte[] buf, int pos) {
        int start = pos;
        do {
            long q = x / 10;
            int d = (int)(x - q * 10);
            buf[pos++] = (byte) ('0' + d);
            x = q;
        } while (x != 0);
        int i = start, j = pos - 1;
        while (i < j) { byte t = buf[i]; buf[i] = buf[j]; buf[j] = t; i++; j--; }
        return pos - start;
    }
    // Worker and Result classes
    static final class Result {
        final String blockLine;
        final String hashHex;
        final long tries;
        Result(String blockLine, String hashHex, long tries) {
            this.blockLine = blockLine; this.hashHex = hashHex; this.tries = tries;
        }
    }
      
    static final class Worker implements Runnable {
        private final byte[] prefix;
        private final String prevHashStr, pseudonym;
        private final int difficulty, id, totalThreads;
        private final AtomicBoolean found;
        private final AtomicReference<Result> winner;
        private final CountDownLatch done;
        private final SplittableRandom rng;

       
        Worker(byte[] prefix, String prevHashStr, String pseudonym,
               int difficulty, int id, int totalThreads,
               AtomicBoolean found, AtomicReference<Result> winner,
               CountDownLatch done, SplittableRandom rng) {
            this.prefix = prefix;
            this.prevHashStr = prevHashStr;
            this.pseudonym = pseudonym;
            this.difficulty = difficulty;
            this.id = id; this.totalThreads = totalThreads;
            this.found = found; this.winner = winner;
            this.done = done; this.rng = rng;
        }
        // Run method
        @Override public void run() {
            try {
                long nonce = Math.floorMod(rng.nextLong(), Long.MAX_VALUE) % totalThreads + id;
                byte[] nonceBuf = new byte[24];
                byte[] hash = new byte[32];
                MessageDigest md = MD.get();
                // Mining loop
                long triesLocal = 0;
                while (!found.get()) {
                    long candidate = nonce;
                    nonce += totalThreads;
                        // Compute hash
                    md.reset();
                    md.update(prefix);
                    int len = writeLongAscii(candidate, nonceBuf, 0);
                    md.update(nonceBuf, 0, len);
                    hash = md.digest();

                    int ld = leadingZeroBits(hash);
                    triesLocal++;
                    // Progress report
                    if ((triesLocal & ((1 << 22) - 1)) == 0) {
                        System.out.printf("... %,d tries; thread %d; ld=%d%n", triesLocal, id, ld);
                    }
                    // Check difficulty
                    if (ld >= difficulty) {
                        String hashHex = toHex(hash);
                        String blockLine = prevHashStr + pseudonym + candidate;
                        if (found.compareAndSet(false, true)) {
                            winner.set(new Result(blockLine, hashHex, triesLocal));
                        }
                        break;
                    }
                }
            } finally { // Signal completion
                done.countDown();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: java Miner <prevHash> <pseudonym> <startDifficulty> <targetDifficulty> [threads]");
            System.exit(1);
        }
       // Parse arguments
        final String prevHashInitial = args[0].toLowerCase();
        final String pseudonym = args[1];
        final int startDifficulty = Integer.parseInt(args[2]);
        final int targetDifficulty = Integer.parseInt(args[3]);
        final int threadsArg = (args.length >= 5) ? Integer.parseInt(args[4]) : 1;
          // Input validation
        if (!pseudonymIsValid(pseudonym)) {
            System.err.println("Invalid pseudonym.");
            System.exit(2);
        }// Validate prevHash
        if (!prevHashInitial.matches("^[0-9a-f]{64}$")) {
            System.err.println("Error: prevHash must be 64 hex characters.");
            System.exit(3);
        }// Validate difficulties
        if (targetDifficulty < startDifficulty) {
            System.err.println("Error: targetDifficulty must be >= startDifficulty.");
            System.exit(4);
        }
       // Main mining loop
        final int T = Math.max(1, threadsArg);
        String prevHash = prevHashInitial;
       // Loop over difficulties
        for (int d = startDifficulty; d <= targetDifficulty; d++) {
            System.out.println("Mining difficulty d = " + d + " with " + T + " thread(s)");

            String prefixStr = prevHash + pseudonym;
            byte[] prefix = prefixStr.getBytes(StandardCharsets.UTF_8);
           // Shared synchronization primitives
            AtomicBoolean found = new AtomicBoolean(false);
            AtomicReference<Result> winner = new AtomicReference<>(null);
            CountDownLatch done = new CountDownLatch(T);
            SplittableRandom base = new SplittableRandom(System.nanoTime());

            for (int i = 0; i < T; i++) {
                SplittableRandom rng = base.split();
                Thread t = new Thread(
                    new Worker(prefix, prevHash, pseudonym, d, i, T, found, winner, done, rng),
                    "miner-" + i);
                t.setDaemon(true);
                t.start();
            }
            // Wait for completion
            done.await();
            Result r = winner.get();
            if (r == null) {
                System.err.println("No winner recorded; aborting.");
                System.exit(6);
            }
           
            System.out.println("FOUND block with difficulty " + d);
            System.out.println("Hash = " + r.hashHex);
            System.out.println("Block line: " + r.blockLine);
            recordBlock(pseudonym, r.blockLine);
            
            // AUTO-STOP AFTER FIRST BLOCK FOUND
            System.out.println("First valid block found. Stopping miner.");
            System.exit(0);
            
        }
    }
}
