package com.atex.plugins.autorelated;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.solr.client.solrj.SolrQuery;

import com.polopoly.cm.app.Editor;
import com.polopoly.cm.app.Viewer;
import com.polopoly.cm.app.search.widget.OSearchResult;
import com.polopoly.cm.app.util.PolicyWidgetUtil;
import com.polopoly.cm.app.widget.OComplexPolicyWidget;
import com.polopoly.cm.client.CMException;
import com.polopoly.cm.policy.ContentPolicy;
import com.polopoly.cm.policy.Policy;
import com.polopoly.cm.policy.PolicyUtil;
import com.polopoly.metadata.Metadata;
import com.polopoly.orchid.OrchidException;
import com.polopoly.orchid.ajax.AjaxEvent;
import com.polopoly.orchid.ajax.JSCallback;
import com.polopoly.orchid.ajax.OAjaxTrigger;
import com.polopoly.orchid.ajax.event.ClickEvent;
import com.polopoly.orchid.ajax.listener.StandardAjaxEventListener;
import com.polopoly.orchid.ajax.trigger.JsEventTriggerType;
import com.polopoly.orchid.ajax.trigger.OAjaxTriggerOnEvent;
import com.polopoly.orchid.context.Device;
import com.polopoly.orchid.context.OrchidContext;
import com.polopoly.orchid.widget.OButton;
import com.polopoly.search.solr.querydecorators.WithDecorators;
import com.polopoly.util.LocaleUtil;

/**
 * OSearchResultWidget
 *
 * @author mnova
 */
public class OSearchResultWidget extends OComplexPolicyWidget implements Viewer, Editor {

    private static final Logger LOGGER = Logger.getLogger(OSearchResultWidget.class.getName());

    private final SearchUtil searchUtil = new SearchUtil();

    private OButton searchButton;
    private OSearchResult searchResultContainer;
    private OAjaxTrigger onClickTrigger;
    private String message;
    private boolean editMode;

    public void initSelf(OrchidContext oc) throws OrchidException {

        message = null;
        editMode = PolicyWidgetUtil.isEditMode(this);

        if (editMode) {
            searchButton = new OButton();
            searchButton.setLabel(LocaleUtil.format("com.atex.plugins.autorelated.searchWidget.button.label", oc.getMessageBundle()));
            addAndInitChild(oc, searchButton);

            searchResultContainer = new OSearchResult(getContentSession(), oc, "search_solrClientInternal");
            searchResultContainer.setListentryContext("myCustomContext");
            searchResultContainer.setCssClass("customSearchResultDiv");
            addAndInitChild(oc, searchResultContainer);

            StandardAjaxEventListener onClickListener = new StandardAjaxEventListener() {
                public boolean triggeredBy(OrchidContext orchidContext, AjaxEvent e) {
                    return e instanceof ClickEvent;
                }

                public JSCallback processEvent(final OrchidContext oc, final AjaxEvent event) throws OrchidException {
                    try {
                        final SolrQuery query = createQuery(oc);
                        if (query != null) {
                            message = null;
                            searchResultContainer.doSearch(oc, query);
                        } else {
                            message = LocaleUtil.format("com.atex.plugins.autorelated.searchWidget.msg.noMetadata", oc.getMessageBundle());
                        }
                    } catch (CMException e) {
                        LOGGER.log(Level.SEVERE, e.getMessage(), e);
                        throw new OrchidException(e);
                    }
                    return null;
                }
            };
            onClickListener.addDecodeWidget(this);
            onClickListener.addRenderWidget(searchResultContainer.getAjaxRenderWidget());
            onClickTrigger = new OAjaxTriggerOnEvent(searchButton, JsEventTriggerType.CLICK);
            onClickTrigger.setFormPostSource(this);
            addAndInitChild(oc, onClickTrigger);
            getTree().registerAjaxEventListener(searchButton, onClickListener);
        }
    }

    private SolrQuery createQuery(final OrchidContext oc) throws CMException {
        final Policy policy = PolicyUtil.getTopPolicy(getPolicy());
        final SiteSettingsPolicy settings = searchUtil.getSiteSettings(policy);
        if (settings.isEnabled()) {
            final Metadata metadata = searchUtil.filterMetadata(settings, getMetadata(policy));
            if (shouldCalculateRelated(policy, metadata)) {
                final String inputTemplate = policy.getInputTemplate().getExternalId().getExternalId();
                final SolrQuery query = searchUtil.getSolrQuery(
                        policy.getContentId()
                              .getContentId(),
                        inputTemplate,
                        metadata,
                        settings.getMinimumMatch());
                if (query != null) {
                    final WithDecorators decorators = settings.getDecorators();
                    excludeRelated((ContentPolicy) policy, decorators);
                    return decorators.decorate(query);
                }
            }
        }
        return null;
    }

    protected boolean shouldCalculateRelated(final Policy policy, final Metadata metadata) {
        return searchUtil.hasMetadata(metadata);
    }

    protected Metadata getMetadata(final Policy policy) {
        return searchUtil.getMetadata(policy);
    }

    protected void excludeRelated(final ContentPolicy policy, final WithDecorators decorators) {
        searchUtil.excludeRelated(policy, decorators);
    }

    public void localRender(OrchidContext oc) throws IOException, OrchidException {
        Device dev = oc.getDevice();
        if (editMode) {
            dev.print("<h2>" + LocaleUtil.format("com.atex.plugins.autorelated.searchWidget.edit.label", oc.getMessageBundle()) + "</h2>");
            dev.print("<div class=\"inlinehelp\">" + LocaleUtil.format("com.atex.plugins.autorelated.searchWidget.edit.message", oc
                    .getMessageBundle()) + "</div>");
            dev.print("&nbsp;");
            searchButton.render(oc);
            if (message != null) {
                dev.print("<div class=\"inlinehelp\">" + message + "</div>");
            } else {
                searchResultContainer.render(oc);
            }
            onClickTrigger.render(oc);
        } else {
            dev.print("<h2>" + LocaleUtil.format("com.atex.plugins.autorelated.searchWidget.view.label", oc.getMessageBundle()) + "</h2>");
            dev.print("<div class=\"inlinehelp\">" + LocaleUtil.format("com.atex.plugins.autorelated.searchWidget.view.message", oc
                    .getMessageBundle()) + "</div>");
            if (message != null) {
                dev.print("<div class=\"inlinehelp\">" + message + "</div>");
            }
        }
    }
}
