package com.atex.plugins.autorelated.decorators;

import org.apache.solr.client.solrj.SolrQuery;

import com.polopoly.cm.ContentId;
import com.polopoly.search.solr.QueryDecorator;
import com.polopoly.search.solr.schema.IndexFields;

/**
 * WithoutContentId
 *
 * @author mnova
 */
public class WithoutContentId implements QueryDecorator {

    private final ContentId[] contentIds;

    public WithoutContentId(final ContentId contentId) {
        this.contentIds = new ContentId[] { contentId };
    }

    public WithoutContentId(final ContentId[] contentIds) {
        this.contentIds = contentIds;
    }

    @Override
    public SolrQuery decorate(SolrQuery query) {
        if (contentIds != null) {
            for (final ContentId id : contentIds) {
                query = query.addFilterQuery("-(" + IndexFields.CONTENT_ID + ":\"" + id.getContentId().getContentIdString() + "\")");
            }
        }
        return query;
    }

}
