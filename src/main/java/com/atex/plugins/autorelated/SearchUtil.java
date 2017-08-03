package com.atex.plugins.autorelated;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.solr.client.solrj.SolrQuery;

import com.atex.plugins.autorelated.decorators.WithoutContentId;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.polopoly.cm.ContentId;
import com.polopoly.cm.app.search.categorization.dimension.AbstractCategoryDimensionInputPolicy;
import com.polopoly.cm.client.CMException;
import com.polopoly.cm.collections.ContentList;
import com.polopoly.cm.policy.ContentPolicy;
import com.polopoly.cm.policy.Policy;
import com.polopoly.cm.policy.PolicyCMServer;
import com.polopoly.metadata.Dimension;
import com.polopoly.metadata.Metadata;
import com.polopoly.metadata.MetadataAware;
import com.polopoly.metadata.util.CategorizationToMetadata;
import com.polopoly.metadata.util.MetadataUtil;
import com.polopoly.search.metadata.DimensionOperator;
import com.polopoly.search.metadata.EntityOperator;
import com.polopoly.search.metadata.MetadataQueryBuilder;
import com.polopoly.search.solr.querydecorators.WithDecorators;
import com.polopoly.search.solr.querydecorators.WithInputTemplate;
import com.polopoly.search.solr.schema.IndexFields;
import com.polopoly.siteengine.tree.PolicyFetcher;
import com.polopoly.siteengine.tree.PolicyFetcherFactory;
import com.polopoly.util.StringUtil;

/**
 * SearchUtil
 *
 * @author mnova
 */
public class SearchUtil {

    private static final Logger LOGGER = Logger.getLogger(SearchUtil.class.getName());

    private static final MetadataQueryBuilder metadataQueryBuilder = new MetadataQueryBuilder();
    private static final String PUBDATE_FIELDNAME = IndexFields.PUBLISHING_DATE.fieldName();

    // prefer newest content, see https://stackoverflow.com/questions/22017616/stronger-boosting-by-date-in-solr
    // usually we use recip(ms(NOW/HOUR, publishingDate),3.16e-11,1,1) for 365 days
    // but we prefer to use 15552000000 which is 180 days.

    private static final String BOOST_FUNC_PARAM = String.format("recip(ms(NOW/HOUR, %s),6.43e-11,1,1)", PUBDATE_FIELDNAME);
    private static final String BOOST_QUERY_PARAM = String.format("%s:[NOW/DAY-1YEAR TO NOW/DAY]", PUBDATE_FIELDNAME);

    public SiteSettingsPolicy getSiteSettings(final Policy policy) throws CMException {
        final PolicyFetcher associatedSitesFetcher = new PolicyFetcherFactory().createFetcherForAssociatedSites(policy.getCMServer());
        final Set<Policy> policies = associatedSitesFetcher.fetch(policy);
        if (policies != null) {
            for (final Policy p : policies) {
                try {
                    final SiteSettingsPolicy settingsPolicy = (SiteSettingsPolicy) ((ContentPolicy) p).getChildPolicy("autorelated");
                    if (settingsPolicy != null) {
                        return settingsPolicy;
                    }
                } catch (CMException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                }
            }
        }
        return null;
    }

    public boolean hasMetadata(final Metadata metadata) {
        if (metadata.getDimensions().size() > 0) {
            for (final Dimension dimension : metadata.getDimensions()) {
                if (dimension.getEntities().size() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public Metadata getMetadata(final Policy policy) {
        try {
            final MetadataAware metadataAware = MetadataUtil.getMetadataAware(policy);
            if (metadataAware != null) {
                return metadataAware.getMetadata();
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "cannot find metadata aware from " + policy.getContentId().getContentIdString() + ": " + e.getMessage(), e);
        }
        return new Metadata();
    }

    public void excludeRelated(final ContentPolicy policy, final WithDecorators decorators) {
        try {
            final List<ContentId> ids = Lists.newArrayList();
            final ContentList related = policy.getContentList("related");
            if (related != null && related.size() > 0) {
                for (int idx = 0; idx < related.size(); idx++) {
                    ids.add(related.getEntry(idx).getReferredContentId());
                }
            }
            final ContentList autorelated = policy.getContentList("autorelated");
            if (autorelated != null && autorelated.size() > 0) {
                for (int idx = 0; idx < autorelated.size(); idx++) {
                    ids.add(autorelated.getEntry(idx).getReferredContentId());
                }
            }
            if (ids.size() > 0) {
                decorators.add(new WithoutContentId(ids.toArray(new ContentId[ids.size()])));
            }
        } catch (CMException e) {
            LOGGER.log(Level.SEVERE, "cannot get related for " + policy.getContentId().getContentIdString() + ": " + e.getMessage(), e);
        }
    }

    public Metadata filterMetadata(final SiteSettingsPolicy settings, final Metadata metadata) {
        try {
            final List<Dimension> configuredDimensions = Lists.newArrayList();
            final PolicyCMServer cmServer = settings.getCMServer();
            final ContentList contentList = settings.getCategorizationContentList();
            for (int idx = 0; idx < contentList.size(); idx++) {
                final ContentId contentId = contentList.getEntry(idx).getReferredContentId();
                final Policy policy = cmServer.getPolicy(contentId);
                final Dimension dimension = from(policy);
                if (dimension == null) {
                    continue;
                }
                configuredDimensions.add(dimension);
            }
            if (configuredDimensions.size() > 0) {
                final Metadata newMetadata = new Metadata();
                for (final Dimension d : configuredDimensions) {
                    final Dimension dimension = metadata.getDimensionById(d.getId());
                    if (dimension != null) {
                        newMetadata.addDimension(dimension);
                    }
                }
                return newMetadata;
            }
        } catch (CMException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
        return metadata;
    }

    private Dimension from(final Policy policy) throws CMException {
        if (policy instanceof AbstractCategoryDimensionInputPolicy) {
            return CategorizationToMetadata.convert(((AbstractCategoryDimensionInputPolicy) policy)
                    .getCategorization());
        }
        return null;
    }

    public SolrQuery getSolrQuery(final ContentId contentId,
                                  final String inputTemplate,
                                  final Metadata metadata,
                                  final String minimumMatch) {

        // see https://cwiki.apache.org/confluence/display/solr/The+Extended+DisMax+Query+Parser
        // and https://cwiki.apache.org/confluence/display/solr/The+DisMax+Query+Parser for the
        // various param for the edismax query parser.

        final String query = metadataQueryBuilder.buildMetadataQuery(metadata, DimensionOperator.NONE, EntityOperator.OR);
        if (!StringUtil.isEmpty(query)) {
            final SolrQuery q = new WithDecorators(
                    new WithInputTemplate(inputTemplate),
                    new WithoutContentId(contentId)
            ).decorate(new SolrQuery(query))
             .clearSorts()
             .setParam("defType", "edismax")

             // prefer newest content, see https://stackoverflow.com/questions/22017616/stronger-boosting-by-date-in-solr
             // usually we use recip(ms(NOW/HOUR, publishingDate),3.16e-11,1,1) for 365 days
             // but we prefer to use 15552000000 which is 180 days.

             .setParam("boost", BOOST_FUNC_PARAM)

             // boost also the latest year articles.

             .setParam("bq", BOOST_QUERY_PARAM);

            final String mm = Strings.nullToEmpty(minimumMatch);
            if (StringUtil.isEmpty(mm)) {
                return q;
            } else {
                return q.setParam("mm", mm);
            }
        }
        return null;
    }

}
