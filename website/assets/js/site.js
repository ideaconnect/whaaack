/* Whaaack! site behaviour. Everything here is progressive enhancement: with JS off the
   page is fully readable, every section is visible and the contact form still posts. */

(function () {
  'use strict';

  document.documentElement.classList.add('js');

  var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ---- scroll reveal --------------------------------------------------------------
  var reveals = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window && !reduced) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('in');
        io.unobserve(entry.target);
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.05 });
    reveals.forEach(function (el) { io.observe(el); });
  } else {
    reveals.forEach(function (el) { el.classList.add('in'); });
  }

  // ---- parallax orchard -----------------------------------------------------------
  // The three layers drift at different rates, mirroring what GameRenderer does on the
  // render thread. Driven off scroll rather than a timer so it costs nothing when idle.
  var layers = [
    { el: document.querySelector('.orchard .sky'), rate: 0.06 },
    { el: document.querySelector('.orchard .trees'), rate: 0.16 },
    { el: document.querySelector('.orchard .hills'), rate: 0.30 }
  ].filter(function (l) { return l.el; });

  if (layers.length && !reduced) {
    var ticking = false;
    var apply = function () {
      var y = window.scrollY || 0;
      layers.forEach(function (l) {
        l.el.style.transform = 'translate3d(' + (-y * l.rate) + 'px, 0, 0)';
      });
      ticking = false;
    };
    window.addEventListener('scroll', function () {
      if (ticking) return;
      ticking = true;
      window.requestAnimationFrame(apply);
    }, { passive: true });
    apply();
  }

  // ---- scattered fruit ------------------------------------------------------------
  // Placed from JS so the markup stays clean and so it can be skipped entirely for
  // reduced-motion or narrow screens, where it would only be clutter.
  // Not gated on reduced-motion: the preference is about movement, not about decoration, so
  // the fruit are still placed and the CSS simply stops them bobbing. Narrow screens skip
  // them because there they would only be clutter behind the copy.
  var field = document.querySelector('.fruit-field');
  if (field && window.innerWidth > 760) {
    var fruits = ['strawberry', 'apple', 'watermelon', 'lemon', 'grape', 'kiwi', 'orange', 'pear'];
    // Fixed positions rather than random: a layout that reshuffles on every reload reads as
    // a glitch. These hug the edges and the gutter between the two columns, so nothing ever
    // lands on the headline or the phone.
    var spots = [
      [0, 26], [2, 80], [9, 97], [49, 4], [54, 94], [97, 20], [99, 70], [91, 99]
    ];
    spots.forEach(function (spot, i) {
      var img = document.createElement('img');
      img.src = 'assets/img/fruits/' + fruits[i % fruits.length] + '.png';
      img.alt = '';
      img.setAttribute('aria-hidden', 'true');
      img.style.left = spot[0] + '%';
      img.style.top = spot[1] + '%';
      img.style.animationDelay = (i * 0.55) + 's';
      img.style.width = (34 + (i % 3) * 12) + 'px';
      field.appendChild(img);
    });
  }

  // ---- contact form -----------------------------------------------------------------
  // The form is a plain POST so that it still works with JS off. The cost of that is the
  // failure path: the browser follows the POST to api.web3forms.com and renders whatever
  // comes back, which for a missing or rejected captcha is raw JSON — no styling, no way
  // back, on the page that is the only out-of-app route for a GDPR or deletion request.
  //
  // So when JS is available (which it must be for the captcha to have rendered at all) the
  // submit is intercepted and the same request is made in the background, leaving the
  // browser on this page whatever happens. With JS off, nothing here runs and the native
  // POST behaves exactly as before.
  var form = document.querySelector('.contact-form');
  if (form && window.fetch && window.FormData) {
    var errorBox = form.querySelector('[data-form-error]');
    var submit = form.querySelector('button[type="submit"]');
    var submitLabel = submit ? submit.textContent : '';

    var fail = function (message) {
      if (!errorBox) return;
      errorBox.textContent = message;
      errorBox.hidden = false;
      if (submit) { submit.disabled = false; submit.textContent = submitLabel; }
    };

    form.addEventListener('submit', function (event) {
      event.preventDefault();
      if (errorBox) errorBox.hidden = true;
      if (submit) { submit.disabled = true; submit.textContent = 'Sending…'; }

      var data = new FormData(form);
      var redirect = data.get('redirect');
      // Sent as JSON: Web3Forms answers a form-encoded POST with a redirect we would have to
      // follow, and answers a JSON one with a body we can actually read and report.
      data.delete('redirect');
      var payload = {};
      data.forEach(function (value, key) { payload[key] = value; });

      fetch(form.action, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body: JSON.stringify(payload)
      }).then(function (response) {
        return response.json().catch(function () { return {}; });
      }).then(function (body) {
        if (body && body.success) {
          window.location.assign(redirect || '/whaaack/contact/thanks/');
          return;
        }
        fail(
          (body && body.message)
            ? body.message
            : 'That did not send. Please check the captcha and try again.'
        );
      }).catch(function () {
        fail('That did not send — the connection failed. Please try again.');
      });
    });
  }

  // ---- current year ---------------------------------------------------------------
  var year = document.querySelector('[data-year]');
  if (year) year.textContent = new Date().getFullYear();
})();
