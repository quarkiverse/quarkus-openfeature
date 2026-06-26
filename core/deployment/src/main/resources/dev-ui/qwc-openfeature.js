import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import { domains } from 'build-time-data';
import '@vaadin/button';
import '@vaadin/combo-box';
import '@vaadin/grid';
import '@vaadin/icon';
import '@vaadin/select';
import '@vaadin/text-area';
import '@vaadin/text-field';

export class QwcOpenfeature extends LitElement {
    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host {
            display: flex;
            flex-direction: column;
            height: 100%;
        }
        .header {
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
        .header .provider-info {
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
        .content {
            display: flex;
            flex: 1;
            min-height: 0;
        }
        .flag-panel {
            flex: 0 0 35%;
            border-right: 1px solid var(--lumo-contrast-10pct);
            display: flex;
            flex-direction: column;
        }
        .flag-search {
            padding: 8px;
            flex-shrink: 0;
        }
        .flag-search vaadin-text-field {
            width: 100%;
        }
        .flag-list {
            flex: 1;
            overflow-y: auto;
        }
        .flag-item {
            padding: 6px 12px;
            cursor: pointer;
            font-size: var(--lumo-font-size-s);
            font-family: var(--lumo-font-family);
        }
        .flag-item:hover {
            background-color: var(--lumo-contrast-5pct);
        }
        .flag-item.selected {
            background-color: var(--lumo-primary-color-10pct);
            color: var(--lumo-primary-text-color);
        }
        .eval-panel {
            flex: 1;
            padding: 16px;
            overflow-y: auto;
            display: flex;
            flex-direction: column;
            gap: 12px;
        }
        .eval-panel h3 {
            margin: 0;
            font-size: var(--lumo-font-size-l);
        }
        .eval-form {
            display: flex;
            flex-direction: column;
            gap: 8px;
        }
        .eval-form vaadin-text-field,
        .eval-form vaadin-text-area,
        .eval-form vaadin-select {
            width: 100%;
        }
        .result {
            background-color: var(--lumo-contrast-5pct);
            border-radius: var(--lumo-border-radius-m);
            padding: 12px;
            font-size: var(--lumo-font-size-s);
        }
        .result-row {
            display: flex;
            gap: 8px;
            padding: 2px 0;
        }
        .result-label {
            font-weight: 600;
            min-width: 80px;
        }
        .result-error {
            color: var(--lumo-error-text-color);
        }
        .unsupported {
            padding: 16px;
            color: var(--lumo-secondary-text-color);
            font-size: var(--lumo-font-size-s);
        }
    `;

    static properties = {
        _selectedDomain: { state: true },
        _providerStatus: { state: true },
        _flags: { state: true },
        _flagsSupported: { state: true },
        _searchFilter: { state: true },
        _selectedFlag: { state: true },
        _evalType: { state: true },
        _evalDefault: { state: true },
        _evalContext: { state: true },
        _evalResult: { state: true },
        _evaluating: { state: true },
    };

    constructor() {
        super();
        this._selectedDomain = domains.length > 0 ? domains[0] : null;
        this._providerStatus = null;
        this._flags = null;
        this._flagsSupported = true;
        this._searchFilter = '';
        this._selectedFlag = null;
        this._evalType = 'boolean';
        this._evalDefault = '';
        this._evalContext = '';
        this._evalResult = null;
        this._evaluating = false;
    }

    connectedCallback() {
        super.connectedCallback();
        if (this._selectedDomain) {
            this._loadDomain(this._selectedDomain);
        }
    }

    _loadDomain(domain) {
        this._providerStatus = null;
        this._flags = null;
        this._flagsSupported = true;
        this._selectedFlag = null;
        this._evalResult = null;

        this.jsonRpc.getProviderStatus({ domain }).then(r => {
            this._providerStatus = r.result;
        });
        this.jsonRpc.getFlags({ domain }).then(r => {
            if (r.result.supported) {
                this._flags = r.result.flags;
                this._flagsSupported = true;
            } else {
                this._flags = [];
                this._flagsSupported = false;
            }
        });
    }

    _onDomainChanged(e) {
        this._selectedDomain = e.detail.value;
        this._loadDomain(this._selectedDomain);
    }

    _onSearchInput(e) {
        this._searchFilter = e.target.value.toLowerCase();
    }

    _onFlagClick(flag) {
        this._selectedFlag = flag.key;
        if (flag.type) {
            this._evalType = flag.type;
        }
        this._evalResult = null;
    }

    _onEvalTypeChanged(e) {
        this._evalType = e.detail.value;
    }

    _onEvalDefaultChanged(e) {
        this._evalDefault = e.target.value;
    }

    _onEvalContextChanged(e) {
        this._evalContext = e.target.value;
    }

    _onEvaluate() {
        if (!this._selectedFlag || !this._selectedDomain) return;
        this._evaluating = true;
        this._evalResult = null;
        this.jsonRpc.evaluate({
            domain: this._selectedDomain,
            key: this._selectedFlag,
            type: this._evalType,
            defaultValue: this._evalDefault,
            contextJson: this._evalContext,
        }).then(r => {
            this._evalResult = r.result;
            this._evaluating = false;
        }).catch(() => {
            this._evaluating = false;
        });
    }

    _onFlagKeyInput(e) {
        this._selectedFlag = e.target.value;
        this._evalResult = null;
    }

    render() {
        return html`
            ${this._renderHeader()}
            <div class="content">
                ${this._renderFlagPanel()}
                ${this._renderEvalPanel()}
            </div>
        `;
    }

    _renderHeader() {
        const domainItems = domains.map(d => ({ label: d, value: d }));
        return html`
            <div class="header">
                <span class="header-label">Domain:</span>
                <vaadin-select
                    .items="${domainItems}"
                    .value="${this._selectedDomain}"
                    @value-changed="${this._onDomainChanged}"
                    theme="small"
                ></vaadin-select>
                <div class="provider-info">
                    ${this._providerStatus ? this._renderProviderInfo() : html`<span>Loading...</span>`}
                </div>
            </div>
        `;
    }

    _renderProviderInfo() {
        const state = this._providerStatus.state;
        const providers = this._providerStatus.providers;
        const names = providers && providers.length > 0 ? providers.join(', ') : 'No provider';
        return html`
            <span class="state-dot state-${state}"></span>
            <span>${state}</span>
            <span class="header-label" style="margin-left: 16px">${providers && providers.length > 1 ? 'Providers:' : 'Provider:'}</span>
            <span class="provider-names">${names}</span>
        `;
    }

    _renderFlagPanel() {
        return html`
            <div class="flag-panel">
                <div class="flag-search">
                    <vaadin-text-field
                        placeholder="Search flags..."
                        clear-button-visible
                        @input="${this._onSearchInput}"
                        theme="small"
                    >
                        <vaadin-icon slot="prefix" icon="lumo:search"></vaadin-icon>
                    </vaadin-text-field>
                </div>
                <div class="flag-list">
                    ${this._renderFlagList()}
                </div>
            </div>
        `;
    }

    _renderFlagList() {
        if (!this._flagsSupported) {
            return html`<div class="unsupported">Flag listing not supported by this provider</div>`;
        }
        if (this._flags === null) {
            return html`<div class="unsupported">Loading...</div>`;
        }
        if (this._flags.length === 0) {
            return html`<div class="unsupported">No flags found</div>`;
        }
        const filtered = this._searchFilter
            ? this._flags.filter(f => f.key.toLowerCase().includes(this._searchFilter))
            : this._flags;
        return filtered.map(flag => html`
            <div class="flag-item ${flag.key === this._selectedFlag ? 'selected' : ''}"
                 @click="${() => this._onFlagClick(flag)}">
                ${flag.key}
            </div>
        `);
    }

    _renderEvalPanel() {
        const typeItems = [
            { label: 'Boolean', value: 'boolean' },
            { label: 'String', value: 'string' },
            { label: 'Integer', value: 'integer' },
            { label: 'Double', value: 'double' },
            { label: 'Object', value: 'object' },
        ];
        return html`
            <div class="eval-panel">
                <h3>Evaluate Flag</h3>
                <div class="eval-form">
                    <vaadin-text-field
                        label="Flag key"
                        .value="${this._selectedFlag || ''}"
                        @input="${this._onFlagKeyInput}"
                        theme="small"
                    ></vaadin-text-field>
                    <vaadin-select
                        label="Type"
                        .items="${typeItems}"
                        .value="${this._evalType}"
                        @value-changed="${this._onEvalTypeChanged}"
                        theme="small"
                    ></vaadin-select>
                    <vaadin-text-field
                        .value="${this._evalDefault}"
                        @input="${this._onEvalDefaultChanged}"
                        theme="small"
                    >
                        <label slot="label">Default value <span title="Returned when the flag cannot be evaluated" style="cursor: help; opacity: 0.7">&#9432;</span></label>
                    </vaadin-text-field>
                    <vaadin-text-area
                        label="Evaluation context (JSON)"
                        .value="${this._evalContext}"
                        @input="${this._onEvalContextChanged}"
                        placeholder='{"targetingKey": "user-123"}'
                        theme="small"
                    ></vaadin-text-area>
                    <vaadin-button
                        theme="primary small"
                        @click="${this._onEvaluate}"
                        ?disabled="${!this._selectedFlag || this._evaluating}"
                    >Evaluate</vaadin-button>
                </div>
                ${this._evalResult ? this._renderResult() : ''}
            </div>
        `;
    }

    _renderResult() {
        const r = this._evalResult;
        if (r.error) {
            return html`
                <div class="result">
                    <div class="result-row result-error">
                        <span class="result-label">Error:</span>
                        <span>${r.error}</span>
                    </div>
                </div>
            `;
        }
        return html`
            <div class="result">
                <div class="result-row">
                    <span class="result-label">Value:</span>
                    <span style="white-space: pre-wrap">${r.value}</span>
                </div>
                ${r.variant ? html`
                    <div class="result-row">
                        <span class="result-label">Variant:</span>
                        <span>${r.variant}</span>
                    </div>
                ` : ''}
                ${r.reason ? html`
                    <div class="result-row">
                        <span class="result-label">Reason:</span>
                        <span>${r.reason}</span>
                    </div>
                ` : ''}
                ${r.errorCode ? html`
                    <div class="result-row result-error">
                        <span class="result-label">Error code:</span>
                        <span>${r.errorCode}</span>
                    </div>
                ` : ''}
                ${r.errorMessage ? html`
                    <div class="result-row result-error">
                        <span class="result-label">Message:</span>
                        <span>${r.errorMessage}</span>
                    </div>
                ` : ''}
            </div>
        `;
    }
}

customElements.define('qwc-openfeature', QwcOpenfeature);
