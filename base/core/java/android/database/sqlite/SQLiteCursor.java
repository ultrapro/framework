/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.database.sqlite;

import android.database.AbstractWindowedCursor;
import android.database.CursorWindow;
import android.database.DatabaseUtils;
import android.os.StrictMode;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/// M: For cursor leak aee warning @{
import com.mediatek.common.aee.IExceptionLog;
import com.mediatek.common.MediatekClassFactory;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
/// @}


/**
 * A Cursor implementation that exposes results from a query on a
 * {@link SQLiteDatabase}.
 *
 * SQLiteCursor is not internally synchronized so code using a SQLiteCursor from multiple
 * threads should perform its own synchronization when using the SQLiteCursor.
 */
public class SQLiteCursor extends AbstractWindowedCursor {
    static final String TAG = "SQLiteCursor";
    static final int NO_COUNT = -1;

    /** The name of the table to edit */
    private final String mEditTable;

    /** The names of the columns in the rows */
    private final String[] mColumns;

    /** The query object for the cursor */
    private final SQLiteQuery mQuery;

    /** The compiled query this cursor came from */
    private final SQLiteCursorDriver mDriver;

    /** The number of rows in the cursor */
    private int mCount = NO_COUNT;

    /** The number of rows that can fit in the cursor window, 0 if unknown */
    private int mCursorWindowCapacity;

    /** A mapping of column names to column indices, to speed up lookups */
    private Map<String, Integer> mColumnNameMap;

    /** Used to find out where a cursor was allocated in case it never got released. */
    private final Throwable mStackTrace;

    /**
     * Execute a query and provide access to its result set through a Cursor
     * interface. For a query such as: {@code SELECT name, birth, phone FROM
     * myTable WHERE ... LIMIT 1,20 ORDER BY...} the column names (name, birth,
     * phone) would be in the projection argument and everything from
     * {@code FROM} onward would be in the params argument.
     *
     * @param db a reference to a Database object that is already constructed
     *     and opened. This param is not used any longer
     * @param editTable the name of the table used for this query
     * @param query the rest of the query terms
     *     cursor is finalized
     * @deprecated use {@link #SQLiteCursor(SQLiteCursorDriver, String, SQLiteQuery)} instead
     */
    @Deprecated
    public SQLiteCursor(SQLiteDatabase db, SQLiteCursorDriver driver,
            String editTable, SQLiteQuery query) {
        this(driver, editTable, query);
    }

    /**
     * Execute a query and provide access to its result set through a Cursor
     * interface. For a query such as: {@code SELECT name, birth, phone FROM
     * myTable WHERE ... LIMIT 1,20 ORDER BY...} the column names (name, birth,
     * phone) would be in the projection argument and everything from
     * {@code FROM} onward would be in the params argument.
     *
     * @param editTable the name of the table used for this query
     * @param query the {@link SQLiteQuery} object associated with this cursor object.
     */
    public SQLiteCursor(SQLiteCursorDriver driver, String editTable, SQLiteQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query object cannot be null");
        }
        if (StrictMode.vmSqliteObjectLeaksEnabled()) {
            mStackTrace = new DatabaseObjectNotClosedException().fillInStackTrace();
        } else {
            mStackTrace = null;
        }
        mDriver = driver;
        mEditTable = editTable;
        mColumnNameMap = null;
        mQuery = query;

        mColumns = query.getColumnNames();
        mRowIdColumnIndex = DatabaseUtils.findRowIdColumnIndex(mColumns);
        
        /** M: when new a cursor, log information */
        if(Log.isLoggable("Debug_Cursor", Log.VERBOSE)) {
            Log.v(TAG,"Cursor open object="+this.hashCode(), new Throwable("stacktrace"));
        }
    }

    /**
     * Get the database that this cursor is associated with.
     * @return the SQLiteDatabase that this cursor is associated with.
     */
    public SQLiteDatabase getDatabase() {
        return mQuery.getDatabase();
    }

    @Override
    public boolean onMove(int oldPosition, int newPosition) {
        // Make sure the row at newPosition is present in the window
        if (mWindow == null || newPosition < mWindow.getStartPosition() ||
                newPosition >= (mWindow.getStartPosition() + mWindow.getNumRows())) {
            fillWindow(newPosition);
        }

        return true;
    }

    @Override
    public int getCount() {
        if (mCount == NO_COUNT) {
            fillWindow(0);
        }
        return mCount;
    }

    private void fillWindow(int requiredPos) {
        clearOrCreateWindow(getDatabase().getPath());

        if (mCount == NO_COUNT) {
            int startPos = DatabaseUtils.cursorPickFillWindowStartPosition(requiredPos, 0);
            mCount = mQuery.fillWindow(mWindow, startPos, requiredPos, true);
            mCursorWindowCapacity = mWindow.getNumRows();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "received count(*) from native_fill_window: " + mCount);
            }
        } else {
            int startPos = DatabaseUtils.cursorPickFillWindowStartPosition(requiredPos,
                    mCursorWindowCapacity);
            mQuery.fillWindow(mWindow, startPos, requiredPos, false);
        }
    }

    @Override
    public int getColumnIndex(String columnName) {
        // Create mColumnNameMap on demand
        if (mColumnNameMap == null) {
            String[] columns = mColumns;
            int columnCount = columns.length;
            HashMap<String, Integer> map = new HashMap<String, Integer>(columnCount, 1);
            for (int i = 0; i < columnCount; i++) {
                map.put(columns[i], i);
            }
            mColumnNameMap = map;
        }

        // Hack according to bug 903852
        final int periodIndex = columnName.lastIndexOf('.');
        if (periodIndex != -1) {
            Exception e = new Exception();
            Log.e(TAG, "requesting column name with table name -- " + columnName, e);
            columnName = columnName.substring(periodIndex + 1);
        }

        Integer i = mColumnNameMap.get(columnName);
        if (i != null) {
            return i.intValue();
        } else {
            return -1;
        }
    }

    @Override
    public String[] getColumnNames() {
        return mColumns;
    }

    @Override
    public void deactivate() {
        super.deactivate();
        mDriver.cursorDeactivated();
    }

    @Override
    public void close() {
    	/** M: when cursor is closed, log inforamtion. It is the same as when new */
        if(Log.isLoggable("Debug_Cursor", Log.VERBOSE)) {
            Log.v(TAG,"Cursor close object="+this.hashCode());
        }
        super.close();
        synchronized (this) {
            mQuery.close();
            mDriver.cursorClosed();
        }
    }

    @Override
    public boolean requery() {
        if (isClosed()) {
            return false;
        }

        synchronized (this) {
            if (!mQuery.getDatabase().isOpen()) {
                return false;
            }

            if (mWindow != null) {
                mWindow.clear();
            }
            mPos = -1;
            mCount = NO_COUNT;

            mDriver.cursorRequeried(this);
        }

        try {
            return super.requery();
        } catch (IllegalStateException e) {
            // for backwards compatibility, just return false
            Log.w(TAG, "requery() failed " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void setWindow(CursorWindow window) {
        super.setWindow(window);
        mCount = NO_COUNT;
    }

    /**
     * Changes the selection arguments. The new values take effect after a call to requery().
     */
    public void setSelectionArguments(String[] selectionArgs) {
        mDriver.setBindArguments(selectionArgs);
    }

    /**
     * M: Read uid from resmon monitored uid list file, 
     *    only report red screen warning for these uid
     * @return true if pid in the resmon monitored list.
     */
    private boolean checkAeeWarningList() {
        int uid = android.os.Process.myUid();
        InputStream inStream = null;
        
        try {
            inStream = new FileInputStream("/data/system/resmon-uid.txt");
            
            if (inStream != null) {
                InputStreamReader inputReader = new InputStreamReader(inStream);
                BufferedReader buffReader = new BufferedReader(inputReader);
                
                String line = buffReader.readLine();
                while (line != null) {
                    if (uid == Integer.valueOf(line)) {
                        return true;
                    }
                    line = buffReader.readLine();
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (inStream != null) {
                try {
                    inStream.close();
                } catch (Exception e) {
                }
            }
        }
    }

    /**
     * Release the native resources, if they haven't been released yet.
     */
    @Override
    protected void finalize() {
        try {
            // if the cursor hasn't been closed yet, close it first
            if (mWindow != null) {
                if (mStackTrace != null) {
                    String sql = mQuery.getSql();
                    int len = sql.length();
                    StrictMode.onSqliteObjectLeaked(
                        "Finalizing a Cursor that has not been deactivated or closed. " +
                        "database = " + mQuery.getDatabase().getLabel() +
                        ", table = " + mEditTable +
                        ", query = " + sql.substring(0, (len > 1000) ? 1000 : len),
                        mStackTrace);
                    /** M: For cursor leak aee warning @{
                        Only show red screen warning for resmon monitored applications */
                    if (checkAeeWarningList()) {
                        try {
                            IExceptionLog exceptionLog = MediatekClassFactory.createInstance(IExceptionLog.class);
                            if (exceptionLog != null) {
                                exceptionLog.systemreport(exceptionLog.AEE_WARNING_JNI, "SQLiteCursor.java", "Finalizing a Cursor that has not been deactivated or closed.", "/data/cursorleak/traces.txt");
                            }
                        } catch (Exception e) {
                            /// M: AEE disabled or failed to allocate AEE object, no need to show message
                        }
                    }
                    /** @} */
                }
                close();
            }
        } finally {
            super.finalize();
        }
    }
}
