package com.mercari.solution.util.gcp;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.StorageObject;
import com.google.auth.Credentials;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.hadoop.util.ChainingHttpRequestInitializer;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.beam.sdk.extensions.gcp.util.RetryHttpRequestInitializer;
import org.apache.parquet.avro.AvroSchemaConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.DelegatingSeekableInputStream;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class StorageUtil {

    public static Storage storage() {
        final HttpTransport transport = new NetHttpTransport();
        final JsonFactory jsonFactory = new JacksonFactory();
        try {
            final Credentials credential = GoogleCredentials.getApplicationDefault();
            final HttpRequestInitializer initializer = new ChainingHttpRequestInitializer(
                    new HttpCredentialsAdapter(credential),
                    // Do not log 404. It clutters the output and is possibly even required by the caller.
                    new RetryHttpRequestInitializer(ImmutableList.of(404)));
            return new Storage.Builder(transport, jsonFactory, initializer)
                    .setApplicationName("StorageClient")
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String readString(final String gcsPath) {
        final String[] paths = parseGcsPath(gcsPath);
        return readString(paths[0], paths[1]);
    }

    public static byte[] readBytes(final String gcsPath) {
        final String[] paths = parseGcsPath(gcsPath);
        return readBytes(paths[0], paths[1]);
    }

    public static void writeString(final String gcsPath, final String content) throws IOException {
        final String[] paths = parseGcsPath(gcsPath);
        storage().objects()
                .insert(paths[0], new StorageObject().setName(paths[1]),
                        new ByteArrayContent("application/octet-stream", content.getBytes()))
                .execute();
    }

    public static void writeString(
            final String gcsPath,
            final String content,
            final Map<String, Object> fields) throws IOException {

        writeString(gcsPath, content, "application/octet-stream", fields, null);
    }

    public static void writeString(
            final String gcsPath,
            final String content,
            final String type,
            final Map<String, Object> fields,
            final Map<String, String> metadata) throws IOException {

        writeBytes(gcsPath, content.getBytes(), type, fields, metadata);
    }

    public static void writeBytes(
            final String gcsPath,
            final byte[] bytes,
            final String contentType,
            final Map<String, Object> fields,
            final Map<String, String> metadata) throws IOException {

        writeBytes(storage(), gcsPath, bytes, contentType, fields, metadata);
    }

    public static void writeBytes(
            Storage storage,
            final String gcsPath,
            final byte[] bytes,
            final String contentType,
            final Map<String, Object> fields,
            final Map<String, String> metadata) throws IOException {

        final String[] paths = parseGcsPath(gcsPath);

        final StorageObject storageObject = new StorageObject()
                .setName(paths[1])
                .setContentType(contentType);
        fields.forEach(storageObject::set);

        if(metadata != null) {
            storageObject.setMetadata(metadata);
        }

        if(storage == null) {
            storage = storage();
        }
        storage.objects()
                .insert(paths[0], storageObject, new ByteArrayContent(contentType, bytes))
                .execute();
    }

    private static String readString(final String bucket, final String object) {
        final byte[] bytes = readBytes(bucket, object);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(final String bucket, final String object) {
        try {
            return ByteStreams.toByteArray(storage()
                    .objects()
                    .get(bucket, object)
                    .executeMediaAsInputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<StorageObject> listFiles(final String gcsPath) {
        final String[] paths = parseGcsPath(gcsPath);
        return listFiles(paths[0], paths[1]);
    }

    public static List<StorageObject> listFiles(final String bucket, final String object) {
        final String prefix;
        if(object.endsWith("*")) {
            prefix = object.replace("*", "");
        } else {
            prefix = object;
        }
        try {
            return storage().objects()
                    .list(bucket).setPrefix(prefix).execute().getItems();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Boolean exists(final String gcsPath) {
        return exists(storage(), gcsPath);
    }

    public static Boolean exists(final Storage storage, final String gcsPath) {
        if(gcsPath == null || !gcsPath.startsWith("gs://")) {
            return null;
        }
        final String[] paths = parseGcsPath(gcsPath);
        try {
            return !storage.objects().get(paths[0], paths[1]).isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    public static String addFilePrefix(String output, String prefix) {
        final String[] paths = output.replaceAll("gs://", "").split("/", -1);
        if(paths.length > 1) {
            return paths[paths.length-1] + prefix;
        }
        return prefix;
    }

    public static String removeDirSuffix(String output) {
        final boolean isgcs = output.startsWith("gs://");
        final String[] paths = output.replaceAll("gs://", "").split("/", -1);
        final StringBuilder sb = new StringBuilder(isgcs ? "gs://" : "");
        final int end = Math.max(paths.length-1, 1);
        for(int i=0; i<end; i++) {
            sb.append(paths[i]);
            sb.append("/");
        }
        return sb.toString();
    }

    public static Schema getAvroSchema(final String gcsPath) {
        final String[] paths = parseGcsPath(gcsPath);
        return getAvroSchema(paths[0], paths[1]);
    }

    public static Schema getAvroSchema(final StorageObject object) {
        return getAvroSchema(object.getBucket(), object.getName());
    }

    public static Schema getAvroSchema(final String bucket, final String object) {
        final DatumReader<GenericRecord> datumReader = new GenericDatumReader<>();
        try(final InputStream is = readStream(bucket, object);
            final DataFileStream<GenericRecord> dataFileReader = new DataFileStream<>(is, datumReader)) {
            return dataFileReader.getSchema();
        } catch (Exception e) {
            return null;
        }
    }

    public static Schema getParquetSchema(final String gcsPath) {
        final String[] paths = parseGcsPath(gcsPath);
        return getParquetSchema(paths[0], paths[1]);
    }

    public static Schema getParquetSchema(final StorageObject object) {
        return getParquetSchema(object.getBucket(), object.getName());
    }

    public static Schema getParquetSchema(final String bucket, final String object) {
        final byte[] bytes = readBytes(bucket, object);
        try(final ParquetFileReader f = ParquetFileReader.open(new ParquetStream(bytes))) {
            return new AvroSchemaConverter().convert(f.getFooter().getFileMetaData().getSchema());
        } catch (Exception e) {
            return null;
        }
    }

    private static InputStream readStream(final String bucket, final String object) {
        try {
            return storage().objects().get(bucket, object).executeMediaAsInputStream();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String[] parseGcsPath(String gcsPath) {
        if(gcsPath == null) {
            throw new IllegalArgumentException("gcsPath must not be null");
        }
        if(!gcsPath.startsWith("gs://")) {
            throw new IllegalArgumentException("gcsPath must start with gs://");
        }
        final String[] paths = gcsPath.replaceAll("gs://", "").split("/", 2);
        if(paths.length != 2) {
            throw new IllegalArgumentException("Illegal gcsPath: " + gcsPath);
        }
        return paths;
    }


    public static class ParquetStream implements InputFile {
        private final byte[] data;

        public class SeekableByteArrayInputStream extends ByteArrayInputStream {

            public SeekableByteArrayInputStream(byte[] buf) {
                super(buf);
            }

            public void setPos(int pos) {
                this.pos = pos;
            }

            public int getPos() {
                return this.pos;
            }
        }

        public ParquetStream(final byte[] data) {
            this.data = data;
        }

        @Override
        public long getLength() {
            return this.data.length;
        }

        @Override
        public SeekableInputStream newStream() {
            return new DelegatingSeekableInputStream(new SeekableByteArrayInputStream(this.data)) {

                @Override
                public void seek(long newPos) {
                    ((SeekableByteArrayInputStream) this.getStream()).setPos(new Long(newPos).intValue());
                }

                @Override
                public long getPos() {
                    return new Integer(((SeekableByteArrayInputStream) this.getStream()).getPos()).longValue();
                }
            };
        }
    }

}
