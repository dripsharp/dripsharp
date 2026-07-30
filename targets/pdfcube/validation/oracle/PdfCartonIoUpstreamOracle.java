import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.NonSeekableRandomAccessReadInputStream;
import org.apache.pdfbox.io.RandomAccess;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.io.RandomAccessReadMemoryMappedFile;
import org.apache.pdfbox.io.RandomAccessReadView;
import org.apache.pdfbox.io.RandomAccessReadWriteBuffer;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.io.SequenceRandomAccessRead;

public final class PdfCartonIoUpstreamOracle {
  private interface ThrowingAction {
    void run() throws Exception;
  }

  private static final class TrackingInputStream extends ByteArrayInputStream {
    private boolean closed;

    TrackingInputStream(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }

  private static final List<String> observations = new ArrayList<String>();

  static {
    observations.add("DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1");
  }

  private PdfCartonIoUpstreamOracle() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Expected an output trace path.");
    }

    observeBuffersSeekViewsAndEof();
    observeNonSeekableAndSequenceReads();
    observeFileBackedAndMemoryMappedReads();
    observeScratchStorageAndLimits();

    Files.write(new File(args[0]).toPath(), observations, StandardCharsets.UTF_8);
    System.out.println(
        "Pinned reviewed PDFBox baseline IO oracle passed: "
            + (observations.size() - 1)
            + " observations.");
  }

  private static void observeBuffersSeekViewsAndEof() throws Exception {
    RandomAccessReadBuffer buffer =
        new RandomAccessReadBuffer(bytes(10, 20, 30, 255, 50));
    observe(
        "buffer",
        "memory-read",
        join(buffer.length(), buffer.getPosition(), buffer.peek(), buffer.getPosition(), buffer.read()));

    buffer.skip(2);
    int afterSkip = buffer.read();
    buffer.rewind(2);
    int afterRewind = buffer.read();
    buffer.seek(99);
    observe(
        "seek-rewind",
        "memory-positioning",
        join(afterSkip, afterRewind, buffer.getPosition(), buffer.length()));
    observe("eof", "memory-end", join(buffer.read(), buffer.isEOF()));

    buffer.seek(0);
    RandomAccessReadView view = buffer.createView(1, 3);
    byte[] selected = new byte[3];
    view.readFully(selected);
    observe("views", "memory-bounds", join(view.length(), view.getPosition(), view.isEOF(), unsigned(selected)));
    observe("failure", "nested-view", failureKind(() -> view.createView(0, 1)));
    view.close();
    buffer.seek(0);
    observe("lifecycle", "view-parent-independent", join(view.isClosed(), buffer.read()));

    observe("failure", "negative-seek", failureKind(() -> buffer.seek(-1)));
    buffer.seek(4);
    observe("failure", "premature-eof", failureKind(() -> buffer.readFully(new byte[2])));
    buffer.close();
    buffer.close();
    observe("lifecycle", "memory-close-idempotent", join(buffer.isClosed(), failureKind(buffer::read)));

    RandomAccessReadWriteBuffer readWrite = new RandomAccessReadWriteBuffer(3);
    readWrite.write(bytes(1, 2, 3, 4, 5, 6, 7));
    readWrite.seek(2);
    byte[] middle = new byte[4];
    int count = readWrite.read(middle, 0, middle.length);
    observe("buffer", "chunked-write-read", join(readWrite.length(), count, unsigned(middle)));
    readWrite.clear();
    readWrite.write(bytes(200, 201, 202));
    readWrite.seek(0);
    byte[] rewritten = new byte[3];
    readWrite.readFully(rewritten);
    observe("buffer", "clear-and-rewrite", join(readWrite.length(), unsigned(rewritten)));
    readWrite.close();
  }

  private static void observeNonSeekableAndSequenceReads() throws Exception {
    byte[] sourceBytes = new byte[5000];
    for (int index = 0; index < sourceBytes.length; index++) {
      sourceBytes[index] = (byte) (index % 251);
    }
    TrackingInputStream source = new TrackingInputStream(sourceBytes);
    NonSeekableRandomAccessReadInputStream input =
        new NonSeekableRandomAccessReadInputStream(source);
    byte[] first = new byte[4200];
    int firstCount = input.read(first, 0, first.length);
    input.rewind(200);
    byte[] replay = new byte[200];
    input.readFully(replay);
    input.skip(1000);
    observe(
        "buffer",
        "non-seekable-rewind",
        join(
            firstCount,
            replay[0] & 0xff,
            replay[199] & 0xff,
            input.getPosition(),
            input.isEOF()));
    observe("failure", "non-seekable-seek", failureKind(() -> input.seek(0)));
    input.close();
    input.close();
    observe(
        "lifecycle",
        "non-seekable-close",
        join(source.closed, input.isClosed(), failureKind(input::read)));

    List<RandomAccessRead> parts =
        Arrays.<RandomAccessRead>asList(
            new RandomAccessReadBuffer(bytes(1, 2)),
            new RandomAccessReadBuffer(bytes(3, 4, 5)));
    SequenceRandomAccessRead sequence = new SequenceRandomAccessRead(parts);
    byte[] combined = new byte[5];
    int count = sequence.read(combined, 0, combined.length);
    sequence.seek(1);
    observe("buffer", "sequence-read", join(count, unsigned(combined), sequence.read()));
    sequence.close();
  }

  private static void observeFileBackedAndMemoryMappedReads() throws Exception {
    Path file = Files.createTempFile("pdfcube-io-differential-", ".bin");
    byte[] bytes = new byte[9000];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = (byte) (index % 253);
    }
    Files.write(file, bytes);
    try {
      RandomAccessReadBufferedFile buffered = new RandomAccessReadBufferedFile(file);
      buffered.seek(4094);
      byte[] boundary = new byte[6];
      int first = buffered.read(boundary, 0, boundary.length);
      int second = buffered.read(boundary, first, boundary.length - first);
      RandomAccessReadView view = buffered.createView(5000, 4);
      byte[] viewBytes = new byte[4];
      view.readFully(viewBytes);
      view.close();
      buffered.seek(0);
      observe(
          "file-backed",
          "buffered-page-and-view",
          join(buffered.length(), first, second, unsigned(boundary), unsigned(viewBytes), buffered.read()));
      buffered.close();
      buffered.close();
      observe(
          "lifecycle",
          "buffered-file-close",
          join(buffered.isClosed(), failureKind(buffered::read)));

      RandomAccessReadMemoryMappedFile mapped = new RandomAccessReadMemoryMappedFile(file);
      mapped.seek(4095);
      byte[] mappedBytes = new byte[4];
      int mappedCount = mapped.read(mappedBytes, 0, mappedBytes.length);
      RandomAccessReadView mappedView = mapped.createView(7000, 3);
      byte[] mappedViewBytes = new byte[3];
      mappedView.readFully(mappedViewBytes);
      mappedView.close();
      mapped.seek(9000);
      observe(
          "memory-mapped",
          "mapped-read-view-eof",
          join(
              mapped.length(),
              mappedCount,
              unsigned(mappedBytes),
              unsigned(mappedViewBytes),
              mapped.read(),
              mapped.isEOF()));
      mapped.close();
      mapped.close();
      observe(
          "lifecycle",
          "memory-map-close",
          join(mapped.isClosed(), failureKind(mapped::read)));
    } finally {
      Files.deleteIfExists(file);
    }
    observe("file-backed", "file-release", String.valueOf(Files.exists(file)));
  }

  private static void observeScratchStorageAndLimits() throws Exception {
    MemoryUsageSetting setting = MemoryUsageSetting.setupMixed(4096, 12288);
    observe(
        "memory-limits",
        "setting",
        join(
            setting.useMainMemory(),
            setting.useTempFile(),
            setting.isMainMemoryRestricted(),
            setting.isStorageRestricted(),
            setting.getMaxMainMemoryBytes(),
            setting.getMaxStorageBytes()));

    ScratchFile limited = new ScratchFile(MemoryUsageSetting.setupMainMemoryOnly(4096));
    RandomAccess limitedBuffer = limited.createBuffer();
    observe(
        "memory-limits",
        "main-memory-overflow",
        failureKind(() -> limitedBuffer.write(new byte[4097])));
    limited.close();
    limited.close();
    observe(
        "lifecycle",
        "scratch-closes-buffer",
        join(limitedBuffer.isClosed(), failureKind(limited::createBuffer)));

    Path directory = Files.createTempDirectory("pdfcube-io-scratch-");
    try {
      ScratchFile scratch =
          new ScratchFile(MemoryUsageSetting.setupTempFileOnly().setTempDir(directory.toFile()));
      RandomAccess buffer = scratch.createBuffer();
      byte[] expected = new byte[5000];
      long expectedSum = 0;
      for (int index = 0; index < expected.length; index++) {
        expected[index] = (byte) (index % 256);
        expectedSum += expected[index] & 0xff;
      }
      buffer.write(expected);
      boolean existedDuringWrite = hasEntries(directory);
      buffer.seek(0);
      byte[] actual = new byte[expected.length];
      buffer.readFully(actual);
      long actualSum = 0;
      for (byte value : actual) {
        actualSum += value & 0xff;
      }
      buffer.clear();
      buffer.write(bytes(33, 44));
      buffer.seek(0);
      observe(
          "scratch-storage",
          "temp-file-roundtrip",
          join(
              existedDuringWrite,
              expectedSum,
              actualSum,
              actual[0] & 0xff,
              actual[actual.length - 1] & 0xff,
              buffer.length(),
              buffer.read()));
      buffer.close();
      buffer.close();
      scratch.close();
      scratch.close();
      observe(
          "lifecycle",
          "scratch-cleanup",
          join(buffer.isClosed(), !hasEntries(directory)));
    } finally {
      deleteEntries(directory);
      Files.deleteIfExists(directory);
    }
  }

  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int index = 0; index < values.length; index++) {
      result[index] = (byte) values[index];
    }
    return result;
  }

  private static String unsigned(byte[] bytes) {
    StringBuilder value = new StringBuilder();
    for (int index = 0; index < bytes.length; index++) {
      if (index > 0) {
        value.append(',');
      }
      value.append(bytes[index] & 0xff);
    }
    return value.toString();
  }

  private static String join(Object... values) {
    StringBuilder value = new StringBuilder();
    for (int index = 0; index < values.length; index++) {
      if (index > 0) {
        value.append('|');
      }
      value.append(String.valueOf(values[index]).toLowerCase());
    }
    return value.toString();
  }

  private static String failureKind(ThrowingAction action) {
    try {
      action.run();
      return "none";
    } catch (EOFException error) {
      return "eof";
    } catch (IOException error) {
      return "io";
    } catch (Exception error) {
      return "other";
    }
  }

  private static boolean hasEntries(Path directory) throws IOException {
    DirectoryStream<Path> entries = Files.newDirectoryStream(directory);
    try {
      return entries.iterator().hasNext();
    } finally {
      entries.close();
    }
  }

  private static void deleteEntries(Path directory) throws IOException {
    DirectoryStream<Path> entries = Files.newDirectoryStream(directory);
    try {
      for (Path entry : entries) {
        Files.deleteIfExists(entry);
      }
    } finally {
      entries.close();
    }
  }

  private static void observe(String family, String id, String value) {
    observations.add(family + "\t" + id + "\t" + value);
  }
}
