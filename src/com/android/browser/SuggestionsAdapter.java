/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.browser;

import com.android.browser.search.SearchEngine;

import android.app.SearchManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.BrowserContract;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * adapter to wrap multiple cursors for url/search completions
 */
public class SuggestionsAdapter extends BaseAdapter implements Filterable,
        OnClickListener {

    static final int TYPE_BOOKMARK = 0;
    static final int TYPE_HISTORY = 1;
    static final int TYPE_SUGGEST_URL = 2;
    static final int TYPE_SEARCH = 3;
    static final int TYPE_SUGGEST = 4;
    static final int TYPE_VOICE_SEARCH = 5;

    private static final String[] COMBINED_PROJECTION =
            {BrowserContract.Combined._ID, BrowserContract.Combined.TITLE,
                    BrowserContract.Combined.URL, BrowserContract.Combined.IS_BOOKMARK};

    private static final String COMBINED_SELECTION =
            "(url LIKE ? OR url LIKE ? OR url LIKE ? OR url LIKE ? OR title LIKE ?)";

    Context mContext;
    Filter mFilter;
    SuggestionResults mMixedResults;
    List<SuggestItem> mSuggestResults, mFilterResults;
    List<CursorSource> mSources;
    boolean mLandscapeMode;
    CompletionListener mListener;
    int mLinesPortrait;
    int mLinesLandscape;
    Object mResultsLock = new Object();
    List<String> mVoiceResults;
    boolean mReverseResults;
    boolean mIncognitoMode;

    interface CompletionListener {

        public void onSearch(String txt);

        public void onSelect(String txt, int type, String extraData);

        public void onFilterComplete(int count);

    }

    public SuggestionsAdapter(Context ctx, CompletionListener listener) {
        mContext = ctx;
        mListener = listener;
        mLinesPortrait = mContext.getResources().
                getInteger(R.integer.max_suggest_lines_portrait);
        mLinesLandscape = mContext.getResources().
                getInteger(R.integer.max_suggest_lines_landscape);
        mFilter = new SuggestFilter();
        addSource(new CombinedCursor());
    }

    void setVoiceResults(List<String> voiceResults) {
        mVoiceResults = voiceResults;
        notifyDataSetChanged();
    }

    public void setLandscapeMode(boolean mode) {
        mLandscapeMode = mode;
        notifyDataSetChanged();
    }

    public void addSource(CursorSource c) {
        if (mSources == null) {
            mSources = new ArrayList<CursorSource>(5);
        }
        mSources.add(c);
    }

    @Override
    public void onClick(View v) {
        SuggestItem item = (SuggestItem) ((View) v.getParent()).getTag();
        if (R.id.icon2 == v.getId()) {
            // replace input field text with suggestion text
            mListener.onSearch(item.title);
        } else {
            mListener.onSelect(
                    (TextUtils.isEmpty(item.url)? item.title : item.url),
                    item.type, item.extra);
        }
    }

    @Override
    public Filter getFilter() {
        return mFilter;
    }

    @Override
    public int getCount() {
        if (mVoiceResults != null) {
            return mVoiceResults.size();
        }
        return (mMixedResults == null) ? 0 : mMixedResults.getLineCount();
    }

    @Override
    public SuggestItem getItem(int position) {
        if (mReverseResults) {
            position = (getCount() - 1) - position;
        }
        if (mVoiceResults != null) {
            SuggestItem item = new SuggestItem(mVoiceResults.get(position),
                    null, TYPE_VOICE_SEARCH);
            item.extra = Integer.toString(position);
            return item;
        }
        if (mMixedResults == null) {
            return null;
        }
        return mMixedResults.items.get(position);
    }

    public void setReverseResults(boolean reverse) {
        mReverseResults = reverse;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.suggestion_item, parent, false);
        }
        bindView(view, getItem(position));
        return view;
    }

    private void bindView(View view, SuggestItem item) {
        // store item for click handling
        view.setTag(item);
        TextView tv1 = (TextView) view.findViewById(android.R.id.text1);
        TextView tv2 = (TextView) view.findViewById(android.R.id.text2);
        ImageView ic1 = (ImageView) view.findViewById(R.id.icon1);
        View ic2 = view.findViewById(R.id.icon2);
        View div = view.findViewById(R.id.divider);
        tv1.setText(item.title);
        if (TextUtils.isEmpty(item.url)) {
            tv2.setVisibility(View.GONE);
        } else {
            tv2.setVisibility(View.VISIBLE);
            tv2.setText(item.url);
        }
        int id = -1;
        switch (item.type) {
            case TYPE_SUGGEST:
            case TYPE_SEARCH:
            case TYPE_VOICE_SEARCH:
                id = R.drawable.ic_search_category_suggest;
                break;
            case TYPE_BOOKMARK:
                id = R.drawable.ic_search_category_bookmark;
                break;
            case TYPE_HISTORY:
                id = R.drawable.ic_search_category_history;
                break;
            case TYPE_SUGGEST_URL:
                id = R.drawable.ic_search_category_browser;
                break;
            default:
                id = -1;
        }
        if (id != -1) {
            ic1.setImageDrawable(mContext.getResources().getDrawable(id));
        }
        ic2.setVisibility(((TYPE_SUGGEST == item.type)
                || (TYPE_SEARCH == item.type)
                || (TYPE_VOICE_SEARCH == item.type))
                ? View.VISIBLE : View.GONE);
        div.setVisibility(ic2.getVisibility());
        ic2.setOnClickListener(this);
        view.findViewById(R.id.suggestion).setOnClickListener(this);
    }

    class SlowFilterTask extends AsyncTask<CharSequence, Void, List<SuggestItem>> {

        @Override
        protected List<SuggestItem> doInBackground(CharSequence... params) {
            SuggestCursor cursor = new SuggestCursor();
            cursor.runQuery(params[0]);
            List<SuggestItem> results = new ArrayList<SuggestItem>();
            int count = cursor.getCount();
            for (int i = 0; i < count; i++) {
                results.add(cursor.getItem());
                cursor.moveToNext();
            }
            cursor.close();
            return results;
        }

        @Override
        protected void onPostExecute(List<SuggestItem> items) {
            mSuggestResults = items;
            mMixedResults = buildSuggestionResults();
            notifyDataSetChanged();
            mListener.onFilterComplete(mMixedResults.getLineCount());
        }
    }

    SuggestionResults buildSuggestionResults() {
        SuggestionResults mixed = new SuggestionResults();
        List<SuggestItem> filter, suggest;
        synchronized (mResultsLock) {
            filter = mFilterResults;
            suggest = mSuggestResults;
        }
        if (filter != null) {
            for (SuggestItem item : filter) {
                mixed.addResult(item);
            }
        }
        if (suggest != null) {
            for (SuggestItem item : suggest) {
                mixed.addResult(item);
            }
        }
        return mixed;
    }

    class SuggestFilter extends Filter {

        @Override
        public CharSequence convertResultToString(Object item) {
            if (item == null) {
                return "";
            }
            SuggestItem sitem = (SuggestItem) item;
            if (sitem.title != null) {
                return sitem.title;
            } else {
                return sitem.url;
            }
        }

        void startSuggestionsAsync(final CharSequence constraint) {
            if (!mIncognitoMode) {
                new SlowFilterTask().execute(constraint);
            }
        }

        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults res = new FilterResults();
            if (mVoiceResults == null) {
                if (TextUtils.isEmpty(constraint)) {
                    res.count = 0;
                    res.values = null;
                    return res;
                }
                startSuggestionsAsync(constraint);
                List<SuggestItem> filterResults = new ArrayList<SuggestItem>();
                if (constraint != null) {
                    for (CursorSource sc : mSources) {
                        sc.runQuery(constraint);
                    }
                    mixResults(filterResults);
                }
                synchronized (mResultsLock) {
                    mFilterResults = filterResults;
                }
                SuggestionResults mixed = buildSuggestionResults();
                res.count = mixed.getLineCount();
                res.values = mixed;
            } else {
                res.count = mVoiceResults.size();
                res.values = mVoiceResults;
            }
            return res;
        }

        void mixResults(List<SuggestItem> results) {
            int maxLines = mLandscapeMode ? mLinesLandscape : mLinesPortrait;
            maxLines = (int) Math.ceil(maxLines / 2.0);
            for (int i = 0; i < mSources.size(); i++) {
                CursorSource s = mSources.get(i);
                int n = Math.min(s.getCount(), maxLines);
                maxLines -= n;
                boolean more = false;
                for (int j = 0; j < n; j++) {
                    results.add(s.getItem());
                    more = s.moveToNext();
                }
            }
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults fresults) {
            if (fresults.values instanceof SuggestionResults) {
                mMixedResults = (SuggestionResults) fresults.values;
                mListener.onFilterComplete(fresults.count);
            }
            notifyDataSetChanged();
        }

    }

    /**
     * sorted list of results of a suggestion query
     *
     */
    class SuggestionResults {

        ArrayList<SuggestItem> items;
        // count per type
        int[] counts;

        SuggestionResults() {
            items = new ArrayList<SuggestItem>(24);
            // n of types:
            counts = new int[5];
        }

        int getTypeCount(int type) {
            return counts[type];
        }

        void addResult(SuggestItem item) {
            int ix = 0;
            while ((ix < items.size()) && (item.type >= items.get(ix).type))
                ix++;
            items.add(ix, item);
            counts[item.type]++;
        }

        int getLineCount() {
            if (mLandscapeMode) {
                return Math.min(mLinesLandscape, items.size());
            } else {
                return Math.min(mLinesPortrait, items.size());
            }
        }

        @Override
        public String toString() {
            if (items == null) return null;
            if (items.size() == 0) return "[]";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                SuggestItem item = items.get(i);
                sb.append(item.type + ": " + item.title);
                if (i < items.size() - 1) {
                    sb.append(", ");
                }
            }
            return sb.toString();
        }
    }

    /**
     * data object to hold suggestion values
     */
    class SuggestItem {
        String title;
        String url;
        int type;
        String extra;

        public SuggestItem(String text, String u, int t) {
            title = text;
            url = u;
            type = t;
        }

    }

    abstract class CursorSource {

        Cursor mCursor;

        boolean moveToNext() {
            return mCursor.moveToNext();
        }

        public abstract void runQuery(CharSequence constraint);

        public abstract SuggestItem getItem();

        public int getCount() {
            return (mCursor != null) ? mCursor.getCount() : 0;
        }

        public void close() {
            if (mCursor != null) {
                mCursor.close();
            }
        }
    }

    /**
     * combined bookmark & history source
     */
    class CombinedCursor extends CursorSource {

        @Override
        public SuggestItem getItem() {
            if ((mCursor != null) && (!mCursor.isAfterLast())) {
                String title = mCursor.getString(1);
                String url = mCursor.getString(2);
                boolean isBookmark = (mCursor.getInt(3) == 1);
                return new SuggestItem(getTitle(title, url), getUrl(title, url),
                        isBookmark ? TYPE_BOOKMARK : TYPE_HISTORY);
            }
            return null;
        }

        @Override
        public void runQuery(CharSequence constraint) {
            // constraint != null
            if (mCursor != null) {
                mCursor.close();
            }
            String like = constraint + "%";
            String[] args = null;
            String selection = null;
            if (like.startsWith("http") || like.startsWith("file")) {
                args = new String[1];
                args[0] = like;
                selection = "url LIKE ?";
            } else {
                args = new String[5];
                args[0] = "http://" + like;
                args[1] = "http://www." + like;
                args[2] = "https://" + like;
                args[3] = "https://www." + like;
                // To match against titles.
                args[4] = like;
                selection = COMBINED_SELECTION;
            }
            Uri.Builder ub = BrowserContract.Combined.CONTENT_URI.buildUpon();
            ub.appendQueryParameter(BrowserContract.PARAM_LIMIT,
                    Integer.toString(Math.max(mLinesLandscape, mLinesPortrait)));
            BookmarkUtils.addAccountInfo(mContext, ub);
            mCursor =
                    mContext.getContentResolver().query(ub.build(), COMBINED_PROJECTION,
                            selection,
                            (constraint != null) ? args : null,
                            BrowserContract.Combined.IS_BOOKMARK + " DESC, " +
                            BrowserContract.Combined.VISITS + " DESC, " +
                            BrowserContract.Combined.DATE_LAST_VISITED + " DESC");
            if (mCursor != null) {
                mCursor.moveToFirst();
            }
        }

        /**
         * Provides the title (text line 1) for a browser suggestion, which should be the
         * webpage title. If the webpage title is empty, returns the stripped url instead.
         *
         * @return the title string to use
         */
        private String getTitle(String title, String url) {
            if (TextUtils.isEmpty(title) || TextUtils.getTrimmedLength(title) == 0) {
                title = UrlUtils.stripUrl(url);
            }
            return title;
        }

        /**
         * Provides the subtitle (text line 2) for a browser suggestion, which should be the
         * webpage url. If the webpage title is empty, then the url should go in the title
         * instead, and the subtitle should be empty, so this would return null.
         *
         * @return the subtitle string to use, or null if none
         */
        private String getUrl(String title, String url) {
            if (TextUtils.isEmpty(title)
                    || TextUtils.getTrimmedLength(title) == 0
                    || title.equals(url)) {
                return null;
            } else {
                return UrlUtils.stripUrl(url);
            }
        }
    }

    class SuggestCursor extends CursorSource {

        @Override
        public SuggestItem getItem() {
            if (mCursor != null) {
                String title = mCursor.getString(
                        mCursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1));
                String text2 = mCursor.getString(
                        mCursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_2));
                String url = mCursor.getString(
                        mCursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_2_URL));
                String uri = mCursor.getString(
                        mCursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA));
                int type = (TextUtils.isEmpty(url)) ? TYPE_SUGGEST : TYPE_SUGGEST_URL;
                SuggestItem item = new SuggestItem(title, url, type);
                item.extra = mCursor.getString(
                        mCursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA));
                return item;
            }
            return null;
        }

        @Override
        public void runQuery(CharSequence constraint) {
            if (mCursor != null) {
                mCursor.close();
            }
            if (!TextUtils.isEmpty(constraint)) {
                SearchEngine searchEngine = BrowserSettings.getInstance().getSearchEngine();
                if (searchEngine != null && searchEngine.supportsSuggestions()) {
                    mCursor = searchEngine.getSuggestions(mContext, constraint.toString());
                    if (mCursor != null) {
                        mCursor.moveToFirst();
                    }
                }
            } else {
                mCursor = null;
            }
        }

    }

    public void clearCache() {
        mFilterResults = null;
        mSuggestResults = null;
    }

    public void setIncognitoMode(boolean incognito) {
        mIncognitoMode = incognito;
        clearCache();
    }
}
