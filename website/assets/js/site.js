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

/* ==================================================================================
   Motion and the playable board.

   Appended rather than woven into the block above so the original behaviour — reveals,
   orchard parallax, scattered fruit, the contact form — stays exactly as it was. Same
   rules apply here: nothing below is required to read the page, and everything that
   moves checks prefers-reduced-motion first.
   ================================================================================== */

(function () {
  'use strict';

  var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ---- scroll progress ------------------------------------------------------------

  var bar = document.querySelector('.scroll-progress span');
  if (bar && !reduced) {
    var barTicking = false;
    var drawBar = function () {
      var doc = document.documentElement;
      var max = doc.scrollHeight - window.innerHeight;
      bar.style.setProperty('--p', max > 0 ? (window.scrollY / max).toFixed(4) : 0);
      barTicking = false;
    };
    window.addEventListener('scroll', function () {
      if (barTicking) return;
      barTicking = true;
      window.requestAnimationFrame(drawBar);
    }, { passive: true });
    drawBar();
  }

  // ---- count up -------------------------------------------------------------------
  // The stats already contain their final value in the markup, so with JS off — or once
  // this has run — they are simply correct. This only animates the way in.

  var counters = document.querySelectorAll('[data-count]');
  if (counters.length && 'IntersectionObserver' in window && !reduced) {
    var countIo = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        var el = entry.target;
        countIo.unobserve(el);
        var target = parseInt(el.getAttribute('data-count'), 10);
        if (!isFinite(target)) return;
        var started = null;
        var step = function (now) {
          if (started === null) started = now;
          var t = Math.min((now - started) / 900, 1);
          // Ease out, so it lands rather than stops.
          el.textContent = Math.round(target * (1 - Math.pow(1 - t, 3)));
          if (t < 1) window.requestAnimationFrame(step);
        };
        window.requestAnimationFrame(step);
      });
    }, { threshold: 0.6 });
    counters.forEach(function (el) { countIo.observe(el); });
  }

  // ---- pointer tilt ---------------------------------------------------------------
  // Only for a fine pointer: on a touch screen there is no hover, and the tilt would
  // either never fire or fire once and stick.

  var tilts = document.querySelectorAll('[data-tilt]');
  if (tilts.length && !reduced && window.matchMedia('(hover: hover) and (pointer: fine)').matches) {
    tilts.forEach(function (el) {
      var reset = function () {
        el.style.setProperty('--rx', '0deg');
        el.style.setProperty('--ry', '0deg');
      };
      el.addEventListener('pointermove', function (event) {
        var box = el.getBoundingClientRect();
        var x = (event.clientX - box.left) / box.width - 0.5;
        var y = (event.clientY - box.top) / box.height - 0.5;
        el.style.setProperty('--rx', (-y * 9).toFixed(2) + 'deg');
        el.style.setProperty('--ry', (x * 11).toFixed(2) + 'deg');
      });
      el.addEventListener('pointerleave', reset);
      reset();
    });
  }

  // ---- the hero video -------------------------------------------------------------
  // Autoplay is in the markup so it starts without waiting for this file. All this does
  // is stop it when it is off screen: a looping video decoding behind three sections of
  // text is a battery cost with nothing on the other side of it.

  var hero = document.querySelector('.phone-video video');
  if (hero && 'IntersectionObserver' in window) {
    if (reduced) {
      hero.removeAttribute('autoplay');
      hero.pause();
    } else {
      new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            // play() rejects when the tab is hidden or autoplay is blocked; the poster
            // stays up in that case, which is a fine outcome and not worth logging.
            var playing = hero.play();
            if (playing && playing.catch) playing.catch(function () {});
          } else {
            hero.pause();
          }
        });
      }, { threshold: 0.15 }).observe(hero);
    }
  }

  // ---- the screenshot fan ---------------------------------------------------------
  // --fan goes 0 (a closed deck) to 1 (spread) as the rail crosses the middle of the
  // viewport. CSS defaults it to 1, so without this the fan is simply open.

  var fan = document.querySelector('[data-fan]');
  if (fan && !reduced) {
    var fanTicking = false;
    var drawFan = function () {
      var box = fan.getBoundingClientRect();
      var centre = box.top + box.height / 2;
      // 0 when the fan's middle is a full viewport below the centre line, 1 at it.
      var progress = 1 - Math.abs(centre - window.innerHeight / 2) / (window.innerHeight * 0.9);
      fan.style.setProperty('--fan', Math.max(0, Math.min(1, progress)).toFixed(3));
      fanTicking = false;
    };
    window.addEventListener('scroll', function () {
      if (fanTicking) return;
      fanTicking = true;
      window.requestAnimationFrame(drawFan);
    }, { passive: true });
    window.addEventListener('resize', drawFan, { passive: true });
    drawFan();
  }

  // ---- the playable board ---------------------------------------------------------
  //
  // A toy of the real thing, and deliberately a toy: sixteen holes, fruit that rise and
  // duck back down, three escapes and it is over, and the score is the milliseconds it
  // lasted. What it does not do is the game's speed curve — it steps up gently and stops,
  // because the point here is to explain the rule in ten seconds, not to beat a visitor.
  //
  // Built entirely from JS so that a browser without it sees no half-built board.

  var board = document.querySelector('[data-demo-board]');
  var startBtn = document.querySelector('[data-demo-start]');
  if (board && startBtn) {
    var FRUITS = ['strawberry', 'apple', 'watermelon', 'lemon', 'grape', 'kiwi',
                  'orange', 'pear', 'cherry', 'banana', 'peach', 'pineapple'];
    var HOLES = 16;
    var MAX_STRIKES = 3;

    var demo = document.getElementById('demo');
    var msOut = document.querySelector('[data-demo-ms]');
    var hitsOut = document.querySelector('[data-demo-hits]');
    var strikeOut = document.querySelector('[data-demo-strikes]');
    var hint = document.querySelector('[data-demo-hint]');

    var holes = [];
    for (var i = 0; i < HOLES; i++) {
      var hole = document.createElement('button');
      hole.type = 'button';
      hole.className = 'hole';
      hole.setAttribute('aria-label', 'Hole ' + (i + 1));
      var fruit = document.createElement('img');
      fruit.className = 'fruit';
      fruit.alt = '';
      fruit.src = 'assets/img/fruits/apple.png';
      var splat = document.createElement('img');
      splat.className = 'splat';
      splat.alt = '';
      splat.src = 'assets/img/splats/splat0.png';
      hole.appendChild(fruit);
      hole.appendChild(splat);
      board.appendChild(hole);
      holes.push({ el: hole, fruit: fruit, splat: splat, timer: 0, live: false });
    }

    var running = false;
    var startedAt = 0;
    var hits = 0;
    var strikes = 0;
    var clock = 0;
    var spawner = 0;

    var paint = function () {
      strikeOut.innerHTML = '';
      for (var s = 0; s < MAX_STRIKES; s++) {
        var dot = document.createElement('i');
        if (s < strikes) dot.className = 'out';
        strikeOut.appendChild(dot);
      }
      hitsOut.textContent = hits + (hits === 1 ? ' hit' : ' hits');
    };

    var duck = function (h, escaped) {
      if (!h.live) return;
      h.live = false;
      h.el.classList.remove('up');
      window.clearTimeout(h.timer);
      if (escaped && running) {
        strikes++;
        h.el.classList.add('miss');
        window.setTimeout(function () { h.el.classList.remove('miss'); }, 400);
        paint();
        if (strikes >= MAX_STRIKES) stop();
      }
    };

    var pop = function () {
      var free = holes.filter(function (h) { return !h.live; });
      if (!free.length) return;
      var h = free[Math.floor(Math.random() * free.length)];
      h.fruit.src = 'assets/img/fruits/' + FRUITS[Math.floor(Math.random() * FRUITS.length)] + '.png';
      h.live = true;
      h.el.classList.remove('hit');
      h.el.classList.add('up');
      // Fruit get quicker as the run goes on, but stop at 620ms: the real game keeps
      // going down to 430ms, which is not a fair ask of a mouse on a web page.
      var alive = Math.max(620, 1500 - (Date.now() - startedAt) / 12);
      h.timer = window.setTimeout(function () { duck(h, true); }, alive);
    };

    var stop = function () {
      running = false;
      window.clearInterval(clock);
      window.clearInterval(spawner);
      holes.forEach(function (h) { duck(h, false); });
      demo.classList.add('over');
      startBtn.textContent = 'Again';
      hint.textContent = 'Three escaped. In the real game the fruit are half that slow by now.';
    };

    var start = function () {
      hits = 0;
      strikes = 0;
      startedAt = Date.now();
      running = true;
      demo.classList.remove('over');
      startBtn.textContent = 'Restart';
      hint.textContent = 'Whack the fruit before it ducks back down.';
      paint();
      msOut.textContent = '0';

      clock = window.setInterval(function () {
        msOut.textContent = String(Date.now() - startedAt);
      }, 33);
      spawner = window.setInterval(function () {
        pop();
        // A second fruit joins once the run has legs, as it does in the game.
        if (Date.now() - startedAt > 6000 && Math.random() < 0.5) pop();
      }, 700);
      pop();
    };

    holes.forEach(function (h) {
      h.el.addEventListener('click', function () {
        if (!running || !h.live) return;
        hits++;
        h.el.classList.add('hit');
        // Restart the splat animation even when the same hole is hit twice running.
        h.splat.src = 'assets/img/splats/splat' + Math.floor(Math.random() * 3) + '.png';
        duck(h, false);
        paint();
      });
    });

    startBtn.addEventListener('click', function () {
      if (running) stop();
      start();
    });

    // Stop when the section scrolls away: an interval ticking every 33ms for a game
    // nobody is looking at is exactly the kind of thing that flattens a phone battery.
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting && running) stop();
        });
      }, { threshold: 0.1 }).observe(demo);
    }

    paint();
  }
})();
