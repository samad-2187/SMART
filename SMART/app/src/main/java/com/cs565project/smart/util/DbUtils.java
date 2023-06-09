package com.cs565project.smart.util;

import android.app.usage.UsageStats;
import android.content.Context;
import android.util.Log;
import android.util.Pair;

import com.cs565project.smart.db.AppDao;
import com.cs565project.smart.db.AppDatabase;
import com.cs565project.smart.db.entities.AppDetails;
import com.cs565project.smart.db.entities.DailyAppUsage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DbUtils {
    public static final String KEY_APPS_UPDATED_IN_DB = "apps_updated_in_db";

    private static void populateAppDetailsInDb(Context context) {
        AppDao dao = AppDatabase.getAppDatabase(context).appDao();
        Set<String> dbApps = new HashSet<>(dao.getAppPackageNames());
        /*for (String a : dbApps) {
            Log.d("Existing Db App", a);
        }*/
        List<AppInfo> installedApps = AppInfo.getAllApps(context.getPackageManager());

        // Populate db with info of installed apps that are missing from db.
        List<AppDetails> toAdd = new ArrayList<>();
        for (AppInfo a: installedApps) {
            if (dbApps.contains(a.getPackageName())) continue;

            String category = AppInfo.NO_CATEGORY;
            try {
                category = PlayStoreUtil.getPlayStoreCategory(a.getPackageName());
            } catch (IOException e) {
                e.printStackTrace();
            }
            AppDetails appDetails = new AppDetails(a.getPackageName(), a.getAppName(), category, -1);
            toAdd.add(appDetails);
            dbApps.add(a.getPackageName());

            Log.d("New db app", a.getPackageName());
        }
        dao.insertAppDetails(toAdd);
        if (toAdd.size() > 0) {
            Log.d("AppInfoUpdate", String.format("Updated category information of %d apps.", toAdd.size()));
        }
        PreferencesHelper.setPreference(context, KEY_APPS_UPDATED_IN_DB, true);
    }

    public static List<Pair<AppDetails, UsageStats>> updateAndGetRestrictedAppsStatus(Context context) {
        AppDao dao = AppDatabase.getAppDatabase(context).appDao();

        // Construct a restricted apps map.
        List<AppDetails> restrictedAppsList = dao.getRestrictedAppDetails();
        Map<String, AppDetails> restrictedApps = new HashMap<>(restrictedAppsList.size());
        for (AppDetails detail : restrictedAppsList) {
            restrictedApps.put(detail.getPackageName(), detail);
        }

        // Compute the results.
        List<Pair<AppDetails, UsageStats>> results = new ArrayList<>();
        List<UsageStats> todayStats = new UsageStatsUtil(context).getMostUsedAppsToday();
        populateAppDetailsInDb(context); // Make sure all apps are in our db.
        // Only collect stats for apps in our db.
        Set<String> dbApps = new HashSet<>(dao.getAppPackageNames());
        List<DailyAppUsage> toInsert = new ArrayList<>(todayStats.size());
        for (UsageStats usageStats : todayStats) {
//             Log.d("Usage stats received", usageStats.getPackageName() + ", " + usageStats.getTotalTimeInForeground()/1000);
            if (!dbApps.contains(usageStats.getPackageName())) continue;
            toInsert.add(new DailyAppUsage(
                    usageStats.getPackageName(),
                    new Date(UsageStatsUtil.getStartOfDayMillis(new Date())),
                    usageStats.getTotalTimeInForeground(),
                    0
            ));
            if (restrictedApps.containsKey(usageStats.getPackageName())) {
                results.add(new Pair<>(restrictedApps.get(usageStats.getPackageName()), usageStats));
            }
        }

        dao.insertAppUsage(toInsert);
        return results;
    }

    public static class SaveRestrictionToDb implements Runnable {

        Context context;
        String packageName;
        int duration;
        Runnable postSave;

        public SaveRestrictionToDb(Context context, String packageName, int duration, Runnable postSave) {
            this.context = context;
            this.packageName = packageName;
            this.duration = duration;
            this.postSave = postSave;
        }

        @Override
        public void run() {
            if (duration == 0) duration = -1;

            AppDao dao = AppDatabase.getAppDatabase(context).appDao();
            AppDetails appDetails = dao.getAppDetails(packageName);
            dao.updateAppDetails(new AppDetails(
                    appDetails.getPackageName(),
                    appDetails.getAppName(),
                    appDetails.getCategory(),
                    duration));
            postSave.run();
        }
    }
}
