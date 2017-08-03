package com.atex.plugins.autorelated;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.solr.client.solrj.SolrQuery;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.polopoly.application.Application;
import com.polopoly.cm.ContentId;
import com.polopoly.cm.app.policy.ContentListModel;
import com.polopoly.cm.client.CMException;
import com.polopoly.cm.client.CMRuntimeException;
import com.polopoly.cm.collections.ContentList;
import com.polopoly.cm.collections.ContentListSimple;
import com.polopoly.cm.collections.ContentListUtil;
import com.polopoly.cm.policy.ContentPolicy;
import com.polopoly.cm.policy.Policy;
import com.polopoly.cm.policy.PolicyCMServer;
import com.polopoly.metadata.Metadata;
import com.polopoly.model.ModelDomain;
import com.polopoly.model.ModelFactory;
import com.polopoly.model.ModelPathUtil;
import com.polopoly.render.RenderRequest;
import com.polopoly.search.solr.SearchClient;
import com.polopoly.search.solr.SearchResult;
import com.polopoly.search.solr.SearchResultPage;
import com.polopoly.search.solr.SolrSearchClient;
import com.polopoly.search.solr.querydecorators.WithDecorators;
import com.polopoly.siteengine.dispatcher.ControllerContext;
import com.polopoly.siteengine.dispatcher.SiteEngine;
import com.polopoly.siteengine.dispatcher.SiteEngineApplication;
import com.polopoly.siteengine.model.TopModel;
import com.polopoly.siteengine.mvc.RenderControllerBase;
import com.polopoly.siteengine.structure.Site;
import com.polopoly.util.StringUtil;

/**
 * AutoRelatedController
 *
 * @author mnova
 */
public class AutoRelatedController extends RenderControllerBase {

    private static final Logger LOGGER = Logger.getLogger(AutoRelatedController.class.getName());

    private static final Cache<String, List<ContentId>> IDS_CACHE =  CacheBuilder.newBuilder()
                                                                                 .maximumSize(1000)
                                                                                 .expireAfterAccess(10, TimeUnit.MINUTES)
                                                                                 .build();

    private final SearchUtil searchUtil = new SearchUtil();

    @Override
    public void populateModelBeforeCacheKey(final RenderRequest request, final TopModel m, final ControllerContext context) {
        super.populateModelBeforeCacheKey(request, m, context);

        if (m.getContext().getPage() != null && !m.getContext().getPage().getPathAfterPage().isEmpty()) {

            final Application application = context.getApplication();
            if (application == null) {
                LOGGER.log(Level.INFO, "No application configured, search is not available.");
                return;
            }
            final PolicyCMServer cmServer = getCmClient(context).getPolicyCMServer();
            final SearchClient searchClient = (SearchClient) application.getApplicationComponent(SolrSearchClient.DEFAULT_COMPOUND_NAME);
            try {
                final SiteSettingsPolicy settings = getSiteSettingsPolicy(m);
                if (settings != null && settings.isEnabled()) {
                    final ContentId contentId = m.getContext().getPage().getPathAfterPage().getLast();
                    final Policy policy = cmServer.getPolicy(contentId);
                    final List<ContentId> results = getResultsFromCacheOrLoadIt(searchClient, settings, policy);
                    if (results.size() > 0) {
                        final ContentList contentList = ContentListUtil.unmodifiableContentList(new ContentListSimple(results));
                        ModelPathUtil.set(m.getLocal(), "related", getContentListModel(contentList));
                    }
                }
            } catch (CMException e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }

    protected Cache<String, List<ContentId>> getIdsCache() {
        return IDS_CACHE;
    }

    protected String getIdsCacheKey(final Policy policy) {
        return policy.getContentId().getContentIdString();
    }

    private List<ContentId> getResultsFromCacheOrLoadIt(final SearchClient searchClient,
                                                        final SiteSettingsPolicy settings,
                                                        final Policy policy) throws CMException {

        final String cacheKey = getSettingsCacheKey(settings) + "-" + getIdsCacheKey(policy);
        try {
            return getIdsCache().get(cacheKey, new Callable<List<ContentId>>() {
                @Override
                public List<ContentId> call() throws Exception {
                    return getResults(searchClient, settings, policy);
                }
            });
        } catch (ExecutionException e) {
            throw new CMException(e);
        }
    }

    private String getSettingsCacheKey(final SiteSettingsPolicy settings) throws CMException {
        final StringBuilder sb = new StringBuilder(settings.getContentId().getContentId().getContentIdString());
        sb.append(";");
        sb.append(settings.getMaxResults());
        sb.append(";");
        sb.append(settings.getMinimumMatch());
        sb.append(";");
        sb.append(getContentListToString(settings.getCategorizationContentList()));
        sb.append(";");
        sb.append(settings.getDecorators().decorate(new SolrQuery(":")).toString());
        return sb.toString();
    }

    private String getContentListToString(final ContentList list) throws CMException {
        final StringBuilder sb = new StringBuilder("[");
        for (int idx = 0; idx < list.size(); idx++) {
            if (idx > 0) {
                sb.append(",");
            }
            final ContentId id = list.getEntry(idx).getReferredContentId();
            sb.append(id.getContentIdString());
        }
        sb.append("]");
        return sb.toString();
    }

    private List<ContentId> getResults(final SearchClient searchClient,
                                       final SiteSettingsPolicy settings,
                                       final Policy policy) throws CMException {

        final ContentId contentId = policy.getContentId().getContentId();
        try {
            final Metadata metadata = searchUtil.filterMetadata(settings, getMetadata(policy));
            if (shouldCalculateRelated(policy, metadata)) {
                final String inputTemplate = policy.getInputTemplate().getExternalId().getExternalId();
                final SolrQuery query = searchUtil.getSolrQuery(contentId, inputTemplate, metadata, settings.getMinimumMatch());
                if (query != null) {
                    final int maxResults = settings.getMaxResults();
                    final WithDecorators decorators = settings.getDecorators();
                    excludeRelated((ContentPolicy) policy, decorators);
                    return performSearch(searchClient, policy.getCMServer(), decorators.decorate(query), maxResults);
                }
            }
        } catch (CMRuntimeException e) {
            LOGGER.log(Level.FINE, "cannot find metadata aware from " + contentId.getContentIdString() + ": " + e.getMessage(), e);
        }
        return Lists.newArrayList();
    }

    protected boolean shouldCalculateRelated(final Policy policy, final Metadata metadata) throws CMException {
        if (searchUtil.hasMetadata(metadata)) {
            final String allowAutorelatedStr = ((ContentPolicy) policy).getComponent("allowAutorelated", "value");
            return StringUtil.isEmpty(allowAutorelatedStr) || Boolean.parseBoolean(allowAutorelatedStr);
        }
        return false;
    }

    protected Metadata getMetadata(final Policy policy) {
        return searchUtil.getMetadata(policy);
    }

    protected void excludeRelated(final ContentPolicy policy, final WithDecorators decorators) {
        searchUtil.excludeRelated(policy, decorators);
    }

    protected SiteSettingsPolicy getSiteSettingsPolicy(final TopModel m) throws CMException {
        final Site site = m.getContext().getSite().getBean();
        if (site instanceof ContentPolicy) {
            return (SiteSettingsPolicy) ((ContentPolicy) site).getChildPolicy("autorelated");
        }
        return null;
    }

    private List<ContentId> performSearch(final SearchClient searchClient,
                                          final PolicyCMServer cmServer,
                                          final SolrQuery query,
                                          final int maxResults) {
        final List <ContentId> results = Lists.newArrayList();

        final SearchResult searchResult = searchClient.search(query, 100 + maxResults);
        for (final SearchResultPage page : searchResult) {
            final List<ContentId> hits = page.getHits();
            for (final ContentId id : hits) {
                try {
                    if (cmServer.contentExists(id)) {
                        if (!results.contains(id)) {
                            results.add(id);
                        }
                    }
                } catch (CMException e) {
                    LOGGER.log(Level.WARNING, "cannot check content " + id.getContentIdString() + ": " + e.getMessage());
                }
                if (results.size() >= maxResults) {
                    return results;
                }
            }
        }

        return results;
    }

    private ContentList getContentListModel(final ContentList contentList) {
        SiteEngineApplication application = SiteEngine.getApplication();
        if (application != null) {
            ModelFactory modelFactory = application.getModelFactory();
            ModelDomain modelDomain = application.getModelDomain();
            if (modelFactory != null && modelDomain != null) {
                return (ContentListModel) modelFactory.createModel(modelDomain, contentList);
            } else {
                LOGGER.fine("Using unwrapped content list. ModelFactory: '" + modelFactory + "', ModelDomain: '" + modelDomain + "'");
            }
        }
        return contentList;
    }

}
