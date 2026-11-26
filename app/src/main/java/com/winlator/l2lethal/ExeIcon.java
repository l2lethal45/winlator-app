package com.winlator.l2lethal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.winlator.container.Container;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * Saca el icono de un .exe de Windows (recursos RT_GROUP_ICON / RT_ICON) y lo guarda como PNG
 * en las carpetas de iconos del contenedor, para que el acceso directo lo muestre.
 */
final class ExeIcon {
    private static final int RT_ICON = 3;
    private static final int RT_GROUP_ICON = 14;

    private ExeIcon() {}

    /** Devuelve el nombre del icono guardado (para "Icon=") o null si no se pudo. */
    static String saveFromShortcut(Container container, String exec) {
        try {
            File exe = exeFromExec(container, exec);
            if (exe == null || !exe.isFile()) return null;
            String base = exe.getName().toLowerCase(Locale.ROOT).replaceAll("\\.exe$", "").replaceAll("[^a-z0-9]+", "-");
            String name = "l2exe-" + base;
            if (new File(container.getIconsDir(64), name + ".png").isFile()) return name;
            Bitmap bmp = extract(exe);
            if (bmp == null) return null;
            for (int size : new int[]{64, 48, 32}) {
                File dir = container.getIconsDir(size);
                dir.mkdirs();
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, size, size, true);
                try (OutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }
            return name;
        }
        catch (Throwable e) {
            return null;
        }
    }

    /** Exec=env WINEPREFIX="..." wine C:\\\\L2\\ Lethal\\\\System\\\\L2.exe  ->  archivo real del contenedor. */
    private static File exeFromExec(Container container, String exec) {
        if (exec == null) return null;
        int w = exec.indexOf(" wine ");
        String path = w >= 0 ? exec.substring(w + 6) : exec;
        path = path.replace("\\\\\\\\", "\u0001").replace("\\\\ ", " ").replace("\u0001", "\\").trim();
        if (path.startsWith("\"")) path = path.substring(1);
        int end = path.toLowerCase(Locale.ROOT).indexOf(".exe");
        if (end < 0 || path.length() < 3 || path.charAt(1) != ':') return null;
        path = path.substring(0, end + 4);
        String drive = path.substring(0, 1).toLowerCase(Locale.ROOT);
        String rest = path.substring(2).replace('\\', '/');
        File wine = new File(container.getRootDir(), ".wine");
        return drive.equals("c") ? new File(wine, "drive_c" + rest) : new File(wine, "dosdevices/" + drive + ":" + rest);
    }

    private static Bitmap extract(File exe) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(exe, "r")) {
            if (u16(f, 0) != 0x5A4D) return null; // MZ
            long pe = u32(f, 0x3C);
            if (u32(f, pe) != 0x4550) return null; // PE\0\0
            int sections = u16(f, pe + 6);
            int optSize = u16(f, pe + 20);
            long opt = pe + 24;
            int magic = u16(f, opt);
            long dataDir = opt + (magic == 0x20B ? 112 : 96);
            long resRva = u32(f, dataDir + 2 * 8);
            if (resRva == 0) return null;
            long secTable = opt + optSize;
            long[][] secs = new long[sections][3];
            for (int i = 0; i < sections; i++) {
                long s = secTable + i * 40L;
                secs[i][0] = u32(f, s + 12); // VirtualAddress
                secs[i][1] = Math.max(u32(f, s + 8), u32(f, s + 16)); // tamaño
                secs[i][2] = u32(f, s + 20); // PointerToRawData
            }
            long resOff = rvaToOffset(secs, resRva);
            if (resOff < 0) return null;

            // grupo de iconos: primer recurso RT_GROUP_ICON
            long grpData = firstLeaf(f, resOff, findEntry(f, resOff, resOff, RT_GROUP_ICON));
            if (grpData < 0) return null;
            long grpOff = rvaToOffset(secs, u32(f, grpData));
            int count = u16(f, grpOff + 4);
            int bestId = -1, bestScore = -1;
            for (int i = 0; i < count; i++) {
                long e = grpOff + 6 + i * 14L;
                int width = f8(f, e);
                if (width == 0) width = 256;
                int bits = u16(f, e + 6);
                int score = width * 100 + bits;
                if (score > bestScore) { bestScore = score; bestId = u16(f, e + 12); }
            }
            if (bestId < 0) return null;

            long iconDir = findEntry(f, resOff, resOff, RT_ICON);
            if (iconDir < 0) return null;
            long idEntry = findEntry(f, resOff, resOff + (iconDir & 0x7FFFFFFFL), bestId);
            long data = firstLeaf(f, resOff, idEntry);
            if (data < 0) return null;
            long off = rvaToOffset(secs, u32(f, data));
            int size = (int) u32(f, data + 4);
            if (off < 0 || size <= 0 || size > 4 * 1024 * 1024) return null;
            byte[] img = new byte[size];
            f.seek(off);
            f.readFully(img);

            if (size > 8 && (img[0] & 0xFF) == 0x89 && img[1] == 'P' && img[2] == 'N' && img[3] == 'G') {
                return BitmapFactory.decodeByteArray(img, 0, size);
            }
            // envolverlo como .ico de una sola imagen (Android sabe leer ICO)
            ByteArrayOutputStream ico = new ByteArrayOutputStream();
            int w = bestScore / 100;
            ico.write(new byte[]{0, 0, 1, 0, 1, 0});
            ico.write(w >= 256 ? 0 : w);
            ico.write(w >= 256 ? 0 : w);
            ico.write(new byte[]{0, 0, 1, 0});
            int bits = bestScore % 100;
            ico.write(bits & 0xFF); ico.write(0);
            ico.write(size & 0xFF); ico.write((size >> 8) & 0xFF); ico.write((size >> 16) & 0xFF); ico.write((size >> 24) & 0xFF);
            ico.write(new byte[]{22, 0, 0, 0});
            ico.write(img);
            byte[] bytes = ico.toByteArray();
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        }
    }

    /** Busca en el directorio (offset absoluto) una entrada con ese id; devuelve su OffsetToData o -1. */
    private static long findEntry(RandomAccessFile f, long resBase, long dir, int id) throws Exception {
        int named = u16(f, dir + 12), ids = u16(f, dir + 14);
        for (int i = 0; i < named + ids; i++) {
            long e = dir + 16 + i * 8L;
            long name = u32(f, e);
            if ((name & 0x80000000L) == 0 && name == id) return u32(f, e + 4);
        }
        return -1;
    }

    /** Baja siempre por la primera entrada hasta llegar a un IMAGE_RESOURCE_DATA_ENTRY (offset absoluto). */
    private static long firstLeaf(RandomAccessFile f, long resBase, long offsetToData) throws Exception {
        if (offsetToData < 0) return -1;
        for (int depth = 0; depth < 4; depth++) {
            if ((offsetToData & 0x80000000L) == 0) return resBase + offsetToData;
            long dir = resBase + (offsetToData & 0x7FFFFFFFL);
            int total = u16(f, dir + 12) + u16(f, dir + 14);
            if (total == 0) return -1;
            offsetToData = u32(f, dir + 16 + 4);
        }
        return -1;
    }

    private static long rvaToOffset(long[][] secs, long rva) {
        for (long[] s : secs) if (rva >= s[0] && rva < s[0] + s[1]) return rva - s[0] + s[2];
        return -1;
    }

    private static int f8(RandomAccessFile f, long pos) throws Exception { f.seek(pos); return f.read() & 0xFF; }
    private static int u16(RandomAccessFile f, long pos) throws Exception {
        f.seek(pos); int a = f.read(), b = f.read(); return (a & 0xFF) | ((b & 0xFF) << 8);
    }
    private static long u32(RandomAccessFile f, long pos) throws Exception {
        f.seek(pos); long v = 0;
        for (int i = 0; i < 4; i++) v |= ((long) (f.read() & 0xFF)) << (8 * i);
        return v;
    }
}
