package eu.sweetlygeek.smsexporter;

import com.jakewharton.threetenabp.AndroidThreeTen;

/**
 * @author bishiboosh
 */

public class Application extends android.app.Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AndroidThreeTen.init(this);
    }
}
