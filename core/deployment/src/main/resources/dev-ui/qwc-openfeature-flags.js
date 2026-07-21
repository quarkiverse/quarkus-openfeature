import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import { domains } from 'build-time-data';
import './qwc-openfeature-selector.js';
import '@vaadin/button';
import '@vaadin/icon';
import '@vaadin/select';
import '@vaadin/text-area';
import '@vaadin/text-field';

export class QwcOpenfeatureFlags extends LitElement {
    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host {
            display: flex;
            flex-direction: column;
            height: 100%;
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
        .flag-key {
            display: flex;
            align-items: center;
            gap: 6px;
        }
        .override-dot {
            display: inline-block;
            width: 6px;
            height: 6px;
            border-radius: 50%;
            background-color: var(--lumo-primary-color);
            flex-shrink: 0;
            cursor: help;
        }
        .override-section {
            border-top: 1px solid var(--lumo-contrast-10pct);
            padding-top: 12px;
        }
        .override-form {
            display: flex;
            flex-direction: column;
            gap: 8px;
        }
        .override-form vaadin-text-field {
            width: 100%;
        }
        .override-buttons {
            display: flex;
            gap: 4px;
        }
        .right-panel {
            flex: 1;
            padding: 16px;
            overflow-y: auto;
            display: flex;
            flex-direction: column;
            gap: 16px;
        }
        .right-panel h3 {
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
        _selectedFlagType: { state: true },
        _evalDefault: { state: true },
        _evalContext: { state: true },
        _evalResult: { state: true },
        _evaluating: { state: true },
        _overrides: { state: true },
        _overrideValue: { state: true },
        _overrideError: { state: true },
    };

    constructor() {
        super();
        this._selectedDomain = domains.length > 0 ? domains[0] : null;
        this._providerStatus = null;
        this._flags = null;
        this._flagsSupported = true;
        this._searchFilter = '';
        this._selectedFlag = null;
        this._selectedFlagType = 'boolean';
        this._evalDefault = '';
        this._evalContext = '';
        this._evalResult = null;
        this._evaluating = false;
        this._overrides = {};
        this._overrideValue = '';
        this._overrideError = null;
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
        this._overrideValue = '';

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
        this._loadOverrides(domain);
    }

    _loadOverrides(domain) {
        this.jsonRpc.getOverrides({ domain }).then(r => {
            this._overrides = r.result.overrides;
            if (this._selectedFlag) {
                this._overrideValue = this._overrides[this._selectedFlag] || '';
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
            this._selectedFlagType = flag.type;
        }
        this._overrideValue = this._overrides[flag.key] || '';
        this._overrideError = null;
        this._evalResult = null;
    }

    _onEvalTypeChanged(e) {
        this._selectedFlagType = e.detail.value;
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
            type: this._selectedFlagType,
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

    _onOverrideValueInput(e) {
        this._overrideValue = e.target.value;
    }

    _onSetOverride(key) {
        this.jsonRpc.setOverride({
            domain: this._selectedDomain,
            key,
            value: this._overrideValue,
            type: this._selectedFlagType,
        }).then(r => {
            if (r.result.success) {
                this._overrideError = null;
                this._loadOverrides(this._selectedDomain);
            } else {
                this._overrideError = r.result.error;
            }
        });
    }

    _onClearOverride(key) {
        this.jsonRpc.clearOverride({
            domain: this._selectedDomain,
            key,
        }).then(() => {
            this._overrideValue = '';
            this._loadOverrides(this._selectedDomain);
        });
    }

    _onClearAllOverrides() {
        this.jsonRpc.clearAllOverrides({
            domain: this._selectedDomain,
        }).then(() => {
            this._overrideValue = '';
            this._loadOverrides(this._selectedDomain);
        });
    }

    _hasOverrides() {
        return Object.keys(this._overrides).length > 0;
    }

    render() {
        return html`
            <qwc-openfeature-selector
                .domains="${domains}"
                .selectedDomain="${this._selectedDomain}"
                .providerStatus="${this._providerStatus}"
                @domain-changed="${this._onDomainChanged}"
            ></qwc-openfeature-selector>
            <div class="content">
                ${this._renderFlagPanel()}
                <div class="right-panel">
                    ${this._renderEvalSection()}
                    ${this._renderOverrideSection()}
                </div>
            </div>
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
        return html`
            ${filtered.map(flag => this._renderFlagItem(flag))}
            ${this._hasOverrides() ? html`
                <vaadin-button
                    theme="small primary"
                    style="margin: 8px 12px 0"
                    @click="${this._onClearAllOverrides}"
                >Clear overrides</vaadin-button>
            ` : ''}
        `;
    }

    _renderFlagItem(flag) {
        const isSelected = flag.key === this._selectedFlag;
        const override = flag.key in this._overrides;
        return html`
            <div class="flag-item ${isSelected ? 'selected' : ''}"
                 @click="${() => this._onFlagClick(flag)}">
                <div class="flag-key">
                    ${flag.key}
                    ${override ? html`<span class="override-dot" title="Overridden"></span>` : ''}
                </div>
            </div>
        `;
    }

    _renderEvalSection() {
        const typeItems = [
            { label: 'Boolean', value: 'boolean' },
            { label: 'String', value: 'string' },
            { label: 'Integer', value: 'integer' },
            { label: 'Double', value: 'double' },
            { label: 'Object', value: 'object' },
        ];
        return html`
            <div>
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
                        .value="${this._selectedFlagType}"
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

    _renderOverrideSection() {
        const override = this._selectedFlag && this._selectedFlag in this._overrides;
        return html`
            <div class="override-section">
                <h3>Override Flag</h3>
                ${this._selectedFlagType === 'object' ? html`
                    <div class="result-error" style="font-size: var(--lumo-font-size-s)">Object flags cannot be overridden at the moment. Please file an issue if you need this.</div>
                ` : ''}
                <div class="override-form">
                    <vaadin-text-field
                        label="Override value"
                        .value="${this._overrideValue}"
                        @input="${this._onOverrideValueInput}"
                        ?disabled="${!this._selectedFlag || this._selectedFlagType === 'object'}"
                        theme="small"
                    ></vaadin-text-field>
                    <div class="override-buttons">
                        <vaadin-button
                            theme="small primary"
                            @click="${() => this._onSetOverride(this._selectedFlag)}"
                            ?disabled="${!this._selectedFlag || this._selectedFlagType === 'object'}"
                        >Override</vaadin-button>
                        ${override ? html`
                            <vaadin-button
                                theme="small primary"
                                @click="${() => this._onClearOverride(this._selectedFlag)}"
                            >Clear</vaadin-button>
                        ` : ''}
                    </div>
                    ${this._overrideError ? html`
                        <div class="result-error" style="font-size: var(--lumo-font-size-s)">${this._overrideError}</div>
                    ` : ''}
                </div>
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

customElements.define('qwc-openfeature-flags', QwcOpenfeatureFlags);
