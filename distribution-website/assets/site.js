// Language toggle (Arabic default, French) and live version info from the association API.
(function () {
  var FR = {
    logoAlt: 'Logo de l’association', title: 'L’application officielle de l’association',
    lead: 'Suivez l’actualité et les activités de l’association, devenez bénévole et participez aux votes — depuis votre téléphone.',
    download: 'Télécharger pour Android (APK)', soon: 'Bientôt disponible', requirements: 'Android 7.0 ou plus récent. Gratuit, sans publicité.',
    shots: 'Captures de l’application', shotHome: 'Accueil (contenu d’exemple)', shotIntro: 'Introduction animée', shotDark: 'Mode sombre (contenu d’exemple)',
    shotNote: 'Captures illustratives avec du contenu d’exemple ; le contenu réel est publié par l’association.',
    featuresTitle: 'Fonctionnalités',
    f1t: 'Actualités et annonces', f1: 'Les dernières nouvelles, lisibles hors ligne.',
    f2t: 'Activités et campagnes', f2: 'Dates, lieux et inscription aux activités.',
    f3t: 'Bénévolat', f3: 'Postulez et suivez votre candidature et vos affectations.',
    f4t: 'Votes', f4: 'Vote sécurisé : une voix par compte vérifié, vote anonyme possible.',
    f5t: 'Vérification via Telegram', f5: 'Sans SMS : partage du numéro via le bot officiel.',
    f6t: 'Photos et vidéos', f6: 'Galerie des activités.',
    f7t: 'Dons', f7: 'Informations officielles de don (aucun paiement dans l’application).',
    f8t: 'Arabe et français', f8: 'Interface entièrement en arabe, français disponible, mode sombre.',
    installTitle: 'Installation',
    s1: 'Appuyez sur le bouton de téléchargement depuis votre téléphone.',
    s2: 'Ouvrez le fichier téléchargé. Si demandé, autorisez le navigateur à installer des applications (Paramètres → Sécurité).',
    s3: 'Appuyez sur « Installer » puis ouvrez l’application.',
    s4: 'Pour mettre à jour, installez la nouvelle version par-dessus l’ancienne — vos données sont conservées.',
    official: 'Téléchargez l’application uniquement depuis cette page.',
    contactTitle: 'Contact', contactSoon: 'Les coordonnées officielles seront publiées prochainement.', privacy: 'Politique de confidentialité',
    version: 'Version ', checksum: 'Empreinte SHA-256 : ',
  };
  var AR = {}; var lang = 'ar';
  try { lang = localStorage.getItem('lang') === 'fr' ? 'fr' : 'ar'; } catch (e) {}
  document.querySelectorAll('[data-i18n]').forEach(function (el) { AR[el.dataset.i18n] = el.textContent; });
  document.querySelectorAll('[data-i18n-alt]').forEach(function (el) { AR[el.dataset.i18nAlt] = el.alt; });
  AR.version = 'الإصدار '; AR.checksum = 'بصمة SHA-256: ';
  var T = function (k) { return (lang === 'fr' ? FR : AR)[k] || AR[k] || ''; };
  var info = {};

  function apply() {
    document.documentElement.lang = lang;
    document.documentElement.dir = lang === 'fr' ? 'ltr' : 'rtl';
    document.querySelectorAll('[data-i18n]').forEach(function (el) { el.textContent = T(el.dataset.i18n); });
    document.querySelectorAll('[data-i18n-alt]').forEach(function (el) { el.alt = T(el.dataset.i18nAlt); });
    var btn = document.getElementById('lang');
    btn.textContent = lang === 'fr' ? 'العربية' : 'Français';
    btn.setAttribute('aria-label', btn.textContent);
    render();
  }

  function render() {
    var dl = document.getElementById('download');
    var v = document.getElementById('version');
    if (info.apk_url && /^https:\/\//.test(info.apk_url)) {
      dl.href = info.apk_url; dl.removeAttribute('aria-disabled');
      v.textContent = info.latest_app_version ? T('version') + info.latest_app_version : '';
    } else { v.textContent = T('soon'); }
    var c = document.getElementById('checksum');
    if (info.apk_sha256) { c.hidden = false; c.textContent = T('checksum') + info.apk_sha256; }
    var lines = [];
    if (info.contact_phone) lines.push(info.contact_phone);
    if (info.contact_email) lines.push(info.contact_email);
    var addr = lang === 'fr' ? info.contact_address_fr || info.contact_address_ar : info.contact_address_ar;
    if (addr) lines.push(addr);
    if (lines.length) document.getElementById('contact-lines').textContent = lines.join(' · ');
  }

  document.getElementById('download').addEventListener('click', function (e) { if (this.getAttribute('aria-disabled')) e.preventDefault(); });
  document.getElementById('lang').addEventListener('click', function () {
    lang = lang === 'fr' ? 'ar' : 'fr';
    try { localStorage.setItem('lang', lang); } catch (e) {}
    apply();
  });

  var base = (document.querySelector('meta[name=api-base]') || {}).content || '';
  if (/^https:\/\//.test(base) && base.indexOf('example.invalid') < 0) {
    fetch(base.replace(/\/$/, '') + '/api/settings').then(function (r) { return r.ok ? r.json() : {}; })
      .then(function (d) { info = d.settings || {}; render(); }).catch(function () {});
  }
  apply();
})();
