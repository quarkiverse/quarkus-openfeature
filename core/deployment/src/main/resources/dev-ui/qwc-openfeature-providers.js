import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import { domains } from 'build-time-data';
import './qwc-openfeature-selector.js';

export class QwcOpenfeatureProviders extends LitElement {
    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host {
            display: flex;
            flex-direction: column;
            height: 100%;
        }
        .event-log {
            flex: 1;
            overflow-y: auto;
            padding: 16px;
        }
        .event-log h3 {
            margin: 0 0 12px 0;
            font-size: var(--lumo-font-size-l);
        }
        table {
            width: 100%;
            border-collapse: collapse;
            font-size: var(--lumo-font-size-s);
        }
        th {
            text-align: left;
            padding: 6px 12px;
            font-weight: 600;
            color: var(--lumo-secondary-text-color);
            border-bottom: 2px solid var(--lumo-contrast-10pct);
        }
        td {
            padding: 6px 12px;
            border-bottom: 1px solid var(--lumo-contrast-10pct);
        }
        .event-type {
            display: flex;
            align-items: center;
            gap: 6px;
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
        .empty-state {
            color: var(--lumo-secondary-text-color);
            font-size: var(--lumo-font-size-s);
        }
    `;

    static properties = {
        _selectedDomain: { state: true },
        _providerStatus: { state: true },
        _events: { state: true },
    };

    constructor() {
        super();
        this._selectedDomain = domains.length > 0 ? domains[0] : null;
        this._providerStatus = null;
        this._events = null;
        this._refreshTimer = null;
    }

    connectedCallback() {
        super.connectedCallback();
        if (this._selectedDomain) {
            this._loadDomain(this._selectedDomain);
        }
        this._refreshTimer = setInterval(() => {
            if (this._selectedDomain) {
                this._loadEvents(this._selectedDomain);
            }
        }, 5000);
    }

    disconnectedCallback() {
        super.disconnectedCallback();
        if (this._refreshTimer) {
            clearInterval(this._refreshTimer);
            this._refreshTimer = null;
        }
    }

    _loadDomain(domain) {
        this._providerStatus = null;
        this._events = null;

        this.jsonRpc.getProviderStatus({ domain }).then(r => {
            this._providerStatus = r.result;
        });
        this._loadEvents(domain);
    }

    _loadEvents(domain) {
        this.jsonRpc.getEvents({ domain }).then(r => {
            this._events = r.result.events;
        });
    }

    _onDomainChanged(e) {
        this._selectedDomain = e.detail.value;
        this._loadDomain(this._selectedDomain);
    }

    _formatTime(timestamp) {
        const d = new Date(timestamp);
        return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    }

    _eventTypeState(type) {
        switch (type) {
            case 'PROVIDER_READY':
            case 'PROVIDER_CONFIGURATION_CHANGED':
                return 'READY';
            case 'PROVIDER_STALE':
                return 'STALE';
            case 'PROVIDER_ERROR':
                return 'ERROR';
            default:
                return 'NOT_READY';
        }
    }

    _eventTypeLabel(type) {
        switch (type) {
            case 'PROVIDER_READY': return 'Ready';
            case 'PROVIDER_CONFIGURATION_CHANGED': return 'Configuration changed';
            case 'PROVIDER_STALE': return 'Stale';
            case 'PROVIDER_ERROR': return 'Error';
            default: return type;
        }
    }

    render() {
        return html`
            <qwc-openfeature-selector
                .domains="${domains}"
                .selectedDomain="${this._selectedDomain}"
                .providerStatus="${this._providerStatus}"
                @domain-changed="${this._onDomainChanged}"
            ></qwc-openfeature-selector>
            <div class="event-log">
                <h3>Provider Events</h3>
                ${this._renderEventLog()}
            </div>
        `;
    }

    _renderEventLog() {
        if (this._events === null) {
            return html`<div class="empty-state">Loading...</div>`;
        }
        if (this._events.length === 0) {
            return html`<div class="empty-state">No events recorded</div>`;
        }
        return html`
            <table>
                <thead>
                    <tr>
                        <th>Time</th>
                        <th>Provider</th>
                        <th>Event</th>
                        <th>Message</th>
                    </tr>
                </thead>
                <tbody>
                    ${this._events.map(event => html`
                        <tr>
                            <td>${this._formatTime(event.timestamp)}</td>
                            <td>${event.provider}</td>
                            <td>
                                <div class="event-type">
                                    <span class="state-dot state-${this._eventTypeState(event.type)}"></span>
                                    <span>${this._eventTypeLabel(event.type)}</span>
                                </div>
                            </td>
                            <td>${event.message}</td>
                        </tr>
                    `)}
                </tbody>
            </table>
        `;
    }
}

customElements.define('qwc-openfeature-providers', QwcOpenfeatureProviders);
