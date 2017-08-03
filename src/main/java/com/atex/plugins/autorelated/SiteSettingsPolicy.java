package com.atex.plugins.autorelated;

import java.util.ListIterator;
import java.util.logging.Logger;

import com.atex.plugins.baseline.collection.searchbased.decorators.Decorator;
import com.atex.plugins.baseline.policy.BaselinePolicy;
import com.polopoly.cm.ContentId;
import com.polopoly.cm.ContentReference;
import com.polopoly.cm.VersionedContentId;
import com.polopoly.cm.client.CMException;
import com.polopoly.cm.collections.ContentList;
import com.polopoly.cm.policy.Policy;
import com.polopoly.search.solr.querydecorators.WithDecorators;

/**
 * SiteSettingsPolicy
 *
 * @author mnova
 */
public class SiteSettingsPolicy extends BaselinePolicy {

    private static final Logger LOGGER = Logger.getLogger(SiteSettingsPolicy.class.getName());

    public boolean isEnabled() {
        return getChildValueAsBoolean("enabled", false);
    }

    public boolean isAjax() {
        return getChildValueAsBoolean("ajax", false);
    }

    public int getMaxResults() {
        return Integer.parseInt(getChildValue("maxResults", "5"));
    }

    public long getCacheTime() {
        final int value = Integer.parseInt(getChildValue("cacheTime", "0"));
        return value * 1000;
    }

    public String getMinimumMatch() {
        return getChildValue("minimumMatch", "");
    }

    public ContentList getCategorizationContentList() throws CMException {
        return getContentList("autorelatedCategorization");
    }

    public ContentList getSourcesContentList() throws CMException {
        return getContentList("autorelatedSources");
    }

    public WithDecorators getDecorators() throws CMException {
        final WithDecorators decorators = new WithDecorators();

        final ListIterator<ContentReference> it = getSourcesContentList().getListIterator();
        while (it.hasNext()) {
            ContentId cid = it.next().getReferredContentId();
            Policy decorator = getDecorator(cid);
            if (decorator instanceof Decorator) {
                decorators.add(((Decorator) decorator).getDecorator());
            }
        }
        return decorators;
    }

    private boolean getChildValueAsBoolean(final String name, final boolean defaultValue) {
        return Boolean.parseBoolean(getChildValue(name, Boolean.toString(defaultValue)));
    }

    private Policy getDecorator(ContentId cid) throws CMException {
        if (getContent().getVersionInfo().isCommitted()) {
            return getCMServer().getPolicy(cid);
        } else {
            VersionedContentId vid = new VersionedContentId(cid, VersionedContentId.LATEST_VERSION);
            return getCMServer().getPolicy(vid);
        }
    }

}
