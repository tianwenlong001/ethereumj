package org.ethereum.net.rlpx;

import com.google.common.base.Throwables;
import org.ethereum.util.RLP;
import org.spongycastle.crypto.BlockCipher;
import org.spongycastle.crypto.Digest;
import org.spongycastle.crypto.StreamCipher;
import org.spongycastle.crypto.engines.AESFastEngine;
import org.spongycastle.crypto.modes.SICBlockCipher;
import org.spongycastle.crypto.params.KeyParameter;
import org.spongycastle.crypto.params.ParametersWithIV;

import java.io.*;

/**
 * Created by devrandom on 2015-04-11.
 */
public class FrameCodec {
    private final StreamCipher enc;
    private final StreamCipher dec;
    private final Digest egressMac;
    private final Digest ingressMac;
    private final BlockCipher macc;
    private final DataInput inp;
    private final OutputStream out;

    public FrameCodec(Handshake.Secrets secrets, InputStream inp, OutputStream out) {
        this.inp = new DataInputStream(inp);
        this.out = out;
        int blockSize = secrets.aes.length * 8;
        enc = new SICBlockCipher(new AESFastEngine());
        enc.init(true, new ParametersWithIV(new KeyParameter(secrets.aes), new byte[blockSize / 8]));
        dec = new SICBlockCipher(new AESFastEngine());
        dec.init(false, new ParametersWithIV(new KeyParameter(secrets.aes), new byte[blockSize / 8]));
        egressMac = secrets.egressMac;
        ingressMac = secrets.ingressMac;
        macc = new AESFastEngine();
        macc.init(true, new KeyParameter(secrets.mac));
    }

    public static class Frame {
        public Frame(long type, int size, InputStream payload) {
            this.type = type;
            this.size = size;
            this.payload = payload;
        }
        long type;
        int size;
        InputStream payload;
    }

    public void writeFrame(Frame frame) {
        byte[] headBuffer = new byte[32];
        byte[] ptype = RLP.encodeInt((int) frame.type); // FIXME encodeLong
        int totalSize = frame.size + ptype.length;
        headBuffer[0] = (byte)(totalSize >> 16);
        headBuffer[1] = (byte)(totalSize >> 8);
        headBuffer[2] = (byte)(totalSize);
        enc.processBytes(headBuffer, 0, 16, headBuffer, 0);
        // FIXME updateMac(egressMac, headBuffer, 0, 16);
        byte[] buff = new byte[256];
        try {
            out.write(headBuffer);
            enc.processBytes(ptype, 0, ptype.length, buff, 0);
            out.write(buff, 0, ptype.length);
            while (true) {
                int n = frame.payload.read(buff);
                if (n <= 0) break;
                enc.processBytes(buff, 0, n, buff, 0);
                out.write(buff, 0, n);
            }
            int padding = 16 - (totalSize % 16);
            byte[] pad = new byte[16];
            if (padding < 16) {
                enc.processBytes(pad, 0, padding, buff, 0);
                out.write(buff, 0, padding);
            }
            out.write(pad); // FIXME frame mac
        } catch (IOException e) {
            Throwables.propagate(e);
        }
    }

    public Frame readFrame() throws IOException {
        byte[] headBuffer = new byte[32];
        inp.readFully(headBuffer);
        dec.processBytes(headBuffer, 0, 16, headBuffer, 0);
        int totalSize;
        totalSize = headBuffer[0];
        totalSize = (totalSize << 8) + headBuffer[1];
        totalSize = (totalSize << 8) + headBuffer[2];
        int padding = 16 - (totalSize % 16);
        if (padding == 16) padding = 0;
        byte[] buffer = new byte[totalSize + padding];
        inp.readFully(buffer);
        dec.processBytes(buffer, 0, buffer.length, buffer, 0);
        int pos = 0;
        long type = RLP.decodeInt(buffer, pos); // FIXME long
        pos = RLP.getNextElementIndex(buffer, pos);
        InputStream payload = new ByteArrayInputStream(buffer, pos, totalSize - pos);
        int size = totalSize - pos;
        return new Frame(type, size, payload);
    }

    private byte[] updateMac(Digest mac, byte[] buf, int offset, int length) {
        byte[] aesBlock = new byte[macc.getBlockSize()];
        mac.doFinal(aesBlock, 0); // FIXME this resets, but go-lang does not reset
        macc.processBlock(aesBlock, 0, aesBlock, 0);
        // FIXME incomplete
        byte[] result = new byte[length];
        return result;
    }
}
