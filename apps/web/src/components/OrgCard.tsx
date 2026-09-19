import { categoryLabel } from "../lib/categories";
import type { OrgDetail } from "../types/org";

interface OrgCardProps {
  org: OrgDetail;
  onBack: () => void;
  onClose: () => void;
}

export function OrgCard({ org, onBack, onClose }: OrgCardProps) {
  const category = categoryLabel(org.category_slug ?? undefined, org.category_name ?? undefined);

  return (
    <article className="org-card" data-testid="org-card" data-id={org.id}>
      <div className="bottom-sheet__body">
        <div className="bottom-sheet__copy">
          <div>
            <h2 className="bottom-sheet__title">{org.name}</h2>
            <p className="bottom-sheet__sub">{category ?? "Organizacija"}</p>
          </div>
        </div>
        <button type="button" className="bottom-sheet__close" aria-label="Zatvori" onClick={onClose}>
          ×
        </button>
      </div>
      <dl className="org-card__fields">
        {org.address ? (
          <div className="org-card__row">
            <dt>Adresa</dt>
            <dd>{org.address.label}</dd>
          </div>
        ) : null}
        {org.floor ? (
          <div className="org-card__row">
            <dt>Sprat</dt>
            <dd>{org.floor}</dd>
          </div>
        ) : null}
        {org.phones.map((phone) => (
          <div className="org-card__row" key={phone}>
            <dt>Telefon</dt>
            <dd>
              <a className="org-card__link" data-testid="org-phone" href={`tel:${phone}`}>
                {phone}
              </a>
            </dd>
          </div>
        ))}
        {org.hours ? (
          <div className="org-card__row">
            <dt>Radno vreme</dt>
            <dd data-testid="org-hours">{org.hours}</dd>
          </div>
        ) : null}
        {org.website ? (
          <div className="org-card__row">
            <dt>Sajt</dt>
            <dd>
              <a className="org-card__link" href={org.website} target="_blank" rel="noreferrer">
                {org.website.replace(/^https?:\/\//, "")}
              </a>
            </dd>
          </div>
        ) : null}
      </dl>
      <button type="button" className="org-card__back" onClick={onBack}>
        ← Zgrada
      </button>
    </article>
  );
}
