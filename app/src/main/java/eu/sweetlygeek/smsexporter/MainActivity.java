package eu.sweetlygeek.smsexporter;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v4.util.ArraySet;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final Uri INBOX_SMS_URI = Uri.parse("content://sms/inbox");

    private static final int PERMISSION_RC = 0;

    private static final String ADDRESS;
    private static final String DATE;
    private static final String DATE_SENT;
    private static final String BODY;

    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            ADDRESS = Telephony.TextBasedSmsColumns.ADDRESS;
            DATE = Telephony.TextBasedSmsColumns.DATE;
            DATE_SENT = Telephony.TextBasedSmsColumns.DATE_SENT;
            BODY = Telephony.TextBasedSmsColumns.BODY;
        } else {
            ADDRESS = "address";
            DATE = "date";
            DATE_SENT = "date_sent";
            BODY = "body";
        }
    }

    private static final String[] SMS_COLUMNS = {ADDRESS, DATE, DATE_SENT, BODY};

    private Button mButton;
    private TextView mStep;
    private View mProgressBar;
    private Gson mGson;
    private File mJsonDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mGson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        mJsonDir = new File(getFilesDir(), "json");
        if (!mJsonDir.exists()) {
            mJsonDir.mkdirs();
        }

        mButton = (Button) findViewById(R.id.export);
        mStep = (TextView) findViewById(R.id.step);
        mProgressBar = findViewById(R.id.progress);

        findViewById(R.id.export).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                        && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    new ExportTask().execute();
                } else {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS}, PERMISSION_RC);
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_RC) {
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }
            new ExportTask().execute();
        }
    }

    private class ExportTask extends AsyncTask<Void, Integer, Void> {

        private ContentResolver mContentResolver;

        public ExportTask() {
            mContentResolver = getContentResolver();
        }

        @Override
        protected void onPreExecute() {
            mButton.setEnabled(false);
            mStep.setVisibility(View.VISIBLE);
            mProgressBar.setVisibility(View.VISIBLE);
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            mButton.setEnabled(true);
            mStep.setVisibility(View.GONE);
            mProgressBar.setVisibility(View.GONE);
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (values != null) {
                if (values.length == 1) {
                    mStep.setText(values[0]);
                } else {
                    Object[] args = new Object[values.length - 1];
                    System.arraycopy(values, 1, args, 0, values.length - 1);
                    mStep.setText(getString(values[0], args));
                }
            }
        }

        @Override
        protected void onCancelled() {
            Toast.makeText(MainActivity.this, R.string.no_sms, Toast.LENGTH_SHORT).show();
        }

        @Override
        protected Void doInBackground(Void... params) {
            Uri smsInboxUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                smsInboxUri = Telephony.Sms.Inbox.CONTENT_URI;
            } else {
                smsInboxUri = INBOX_SMS_URI;
            }
            publishProgress(R.string.getting_sms);
            // List SMS
            List<SmsInfo> smsInfos = new ArrayList<>();
            Multimap<String, SmsInfo> smsInfosByNumbers = ArrayListMultimap.create();
            Cursor c = mContentResolver.query(smsInboxUri, SMS_COLUMNS, null, null, DATE_SENT + " ASC");
            if (c != null) {
                int size = c.getCount();
                if (size == 0) {
                    cancel(false);
                    return null;
                }
                int count = 0;
                while (c.moveToNext()) {
                    String phoneNumber = c.getString(c.getColumnIndex(ADDRESS));
                    long dateReceived = c.getLong(c.getColumnIndex(DATE));
                    long dateSent = c.getLong(c.getColumnIndex(DATE_SENT));
                    String body = c.getString(c.getColumnIndex(BODY));
                    SmsInfo smsInfo = new SmsInfo(phoneNumber, dateReceived, dateSent, body);
                    smsInfos.add(smsInfo);
                    if (Patterns.PHONE.matcher(phoneNumber).matches()) {
                        smsInfosByNumbers.put(phoneNumber, smsInfo);
                    }

                    count++;
                    publishProgress(R.string.sms_progress, count, size);
                }
                c.close();
            }
            // Resolve phone numbers
            publishProgress(R.string.getting_contacts);
            Set<PersonInfo> persons = new ArraySet<>();
            int size = smsInfosByNumbers.keySet().size();
            int count = 0;
            for (Map.Entry<String, Collection<SmsInfo>> entry : smsInfosByNumbers.asMap().entrySet()) {
                Uri lookupUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(entry.getKey()));
                c = mContentResolver.query(lookupUri,
                        new String[]{ContactsContract.CommonDataKinds.Identity.DISPLAY_NAME, ContactsContract.CommonDataKinds.Identity.PHOTO_THUMBNAIL_URI},
                        null, null, null);
                if (c != null) {
                    if (c.moveToFirst()) {
                        String name = c.getString(0);
                        if (!TextUtils.isEmpty(name)) {
                            String uuid = UUID.randomUUID().toString();
                            PersonInfo personInfo = new PersonInfo(uuid, name);
                            persons.add(personInfo);
                            String thumbPath = c.getString(1);
                            personInfo.base64Thumbnail = extractThumbnail(thumbPath);
                            for (SmsInfo smsInfo : entry.getValue()) {
                                smsInfo.personUUID = uuid;
                            }
                        }
                    }
                    c.close();
                }
                count++;
                publishProgress(R.string.contacts_progress, count, size);
            }
            // Transform sms infos to json
            publishProgress(R.string.export_sms);
            Result result = new Result(smsInfos, persons);
            File jsonFile = new File(mJsonDir, "contacts.json");
            FileWriter fw = null;
            try {
                fw = new FileWriter(jsonFile);
                mGson.toJson(result, fw);
                fw.flush();

                // Send
                Uri contentUri = FileProvider.getUriForFile(MainActivity.this, "eu.sweetlygeek.smsexporter.fileprovider", jsonFile);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setData(contentUri);
                intent.putExtra(Intent.EXTRA_STREAM, contentUri);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooserIntent = Intent.createChooser(intent, "Envoyer le JSON");
                startActivity(chooserIntent);
            } catch (IOException e) {
                Log.w(TAG, "Unable to create JSON", e);
            } finally {
                try {
                    Closeables.close(fw, true);
                } catch (IOException e) {
                    // Silent
                }
            }
            return null;
        }

        private String extractThumbnail(String thumbPath) {
            if (!TextUtils.isEmpty(thumbPath)) {
                InputStream is = null;
                Base64OutputStream os = null;
                try {
                    is = mContentResolver.openInputStream(Uri.parse(thumbPath));
                    if (is != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        os = new Base64OutputStream(baos, Base64.NO_WRAP);
                        ByteStreams.copy(is, os);
                        os.flush();
                        return baos.toString("US-ASCII");
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Unable to get thumbnail", e);
                } finally {
                    try {
                        if (os != null) {
                            os.close();
                        }
                        if (is != null) {
                            is.close();
                        }
                    } catch (IOException e) {
                        // Silent
                    }
                }
            }
            return null;
        }
    }

    private static class SmsInfo {
        private String phoneNumber;
        private long dateReceived;
        private long dateSent;
        private String body;
        private String personUUID;

        public SmsInfo(String phoneNumber, long dateReceived, long dateSent, String body) {
            this.phoneNumber = phoneNumber;
            this.dateReceived = dateReceived;
            this.dateSent = dateSent;
            this.body = body;
        }
    }

    private static class PersonInfo {
        private String uuid;
        private String name;
        private String base64Thumbnail;

        public PersonInfo(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            PersonInfo that = (PersonInfo) o;

            return uuid != null ? uuid.equals(that.uuid) : that.uuid == null;

        }

        @Override
        public int hashCode() {
            return uuid != null ? uuid.hashCode() : 0;
        }
    }

    private static class Result {
        Collection<SmsInfo> sms;
        Collection<PersonInfo> persons;

        public Result(Collection<SmsInfo> sms, Collection<PersonInfo> persons) {
            this.sms = sms;
            this.persons = persons;
        }
    }
}
