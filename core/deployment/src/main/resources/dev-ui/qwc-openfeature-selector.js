import { LitElement, html, css } from 'lit';
import '@vaadin/select';

export class QwcOpenfeatureSelector extends LitElement {
    static styles = css`
        :host {
            display: flex;
            align-items: center;
            gap: 16px;
            padding: 8px 16px;
            border-bottom: 1px solid var(--lumo-contrast-10pct);
            flex-shrink: 0;
        }
        .header-label {
            font-size: var(--lumo-font-size-s);
            font-weight: 600;
            color: var(--lumo-secondary-text-color);
        }
        .provider-info {
            display: flex;
            align-items: center;
            gap: 8px;
            font-size: var(--lumo-font-size-s);
            color: var(--lumo-secondary-text-color);
        }
        .state-dot {
            display: inline-block;
            width: 8px;
            height: 8px;
            border-radius: 50%;
        }
        .state-READY { background-color: var(--lumo-success-color); }
        .state-STALE { background-color: var(--lumo-primary-color); }
        .state-ERROR { background-color: var(--lumo-error-color); }
        .state-NOT_READY { background-color: var(--lumo-contrast-30pct); }
        .provider-names {
            color: var(--lumo-body-text-color);
        }
    `;

    static properties = {
        domains: { type: Array },
        selectedDomain: { type: String },
        providerStatus: { type: Object },
    };

    _onDomainChanged(e) {
        this.dispatchEvent(new CustomEvent('domain-changed', {
            detail: { value: e.detail.value },
            bubbles: true,
            composed: true,
        }));
    }

    render() {
        const domainItems = (this.domains || []).map(d => ({ label: d, value: d }));
        return html`
            <span class="header-label">Domain:</span>
            <vaadin-select
                .items="${domainItems}"
                .value="${this.selectedDomain}"
                @value-changed="${this._onDomainChanged}"
                theme="small"
            ></vaadin-select>
            <div class="provider-info">
                ${this.providerStatus ? this._renderProviderInfo() : html`<span>Loading...</span>`}
            </div>
        `;
    }

    _renderProviderInfo() {
        const state = this.providerStatus.state;
        const providers = this.providerStatus.providers;
        const names = providers && providers.length > 0 ? providers.join(', ') : 'No provider';
        return html`
            <span class="state-dot state-${state}"></span>
            <span>${state}</span>
            <span class="header-label" style="margin-left: 16px">${providers && providers.length > 1 ? 'Providers:' : 'Provider:'}</span>
            <span class="provider-names">${names}</span>
        `;
    }
}

customElements.define('qwc-openfeature-selector', QwcOpenfeatureSelector);
