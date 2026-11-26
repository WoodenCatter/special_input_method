var Button = {
	Button: function(options) {
		if(typeof options.cooldown == 'number') {
			this.data_cooldown = options.cooldown;
		}
		this.data_remaining = 0;
		if(typeof options.click == 'function') {
			this.data_handler = options.click;
		}

		var el = $('<div>')
			.attr('id', typeof(options.id) != 'undefined' ? options.id : "BTN_" + Engine.getGuid())
			.addClass('button')
			.text(typeof(options.text) != 'undefined' ? options.text : "button")
			.click(function() {
				var activeEv = (typeof Events !== 'undefined' && Events.activeEvent) ? Events.activeEvent() : null;
				if (activeEv && activeEv.eventPanel && activeEv.eventPanel.length && !activeEv._closing) {
					if (!$(this).closest('.eventPanel').is(activeEv.eventPanel)) {
						return;
					}
				}
				if(!$(this).hasClass('disabled')) {
					Button.cooldown($(this));
					$(this).data("handler")($(this));
				}
			})
			.data("handler",  typeof options.click == 'function' ? options.click : function() { Engine.log("click"); })
			.data("remaining", 0)
			.data("cooldown", typeof options.cooldown == 'number' ? options.cooldown : 0)
			.data('boosted', options.boosted ?? (() => false));

		el.append($("<div>").addClass('cooldown'));

		// waiting for expiry of residual cooldown detected in state
		Button.cooldown(el, 'state');

		if(options.cost) {
			var ttPos = options.ttPos ? options.ttPos : "bottom right";
			var costTooltip = $('<div>').addClass('tooltip ' + ttPos);
			for(var k in options.cost) {
				$("<div>").addClass('row_key').text(_(k)).appendTo(costTooltip);
				$("<div>").addClass('row_val').text(options.cost[k]).appendTo(costTooltip);
			}
			if(costTooltip.children().length > 0) {
				costTooltip.appendTo(el);
			}
		}

		if(options.width) {
			el.css('width', options.width);
		}

		return el;
	},

	saveCooldown: true,

	setDisabled: function(btn, disabled) {
		if(btn) {
			if(!disabled && !btn.data('onCooldown')) {
				btn.removeClass('disabled');
			} else if(disabled) {
				btn.addClass('disabled');
			}
			btn.data('disabled', disabled);
		}
	},

	isDisabled: function(btn) {
		if(btn) {
			return btn.data('disabled') === true;
		}
		return false;
	},

	cooldown: function(btn, option) {
		var cd = btn.data("cooldown");
		if (btn.data('boosted')()) {
			cd /= 2;
		}
		var id = 'cooldown.'+ btn.attr('id');
		if(cd > 0) {
			if(typeof option == 'number') {
				cd = option;
			}
			// param "start" takes value from cooldown time if not specified
			var start, left;
			switch(option){
				// a switch will allow for several uses of cooldown function
				case 'state':
					if(!$SM.get(id)){
						return;
					}
					start = Math.min($SM.get(id), cd);
					left = (start / cd).toFixed(4);
					break;
				default:
					start = cd;
					left = 1;
			}
			Button.clearCooldown(btn);
			var saveCd = Boolean(Button.saveCooldown);
			if(saveCd){
				$SM.set(id, start);
			}
			var time = start;
			if (Engine.options.doubleTime){
				time /= 2;
			}
			var totalMs = Math.max(50, time * 1000);
			var remainingMs = totalMs;
			var leftFrac = parseFloat(left);
			var cdEl = $('div.cooldown', btn);
			cdEl.stop(true, false).css('width', (leftFrac * 100) + '%');
			var saveAccum = 0;
			var tickId = window.setInterval(function() {
				if (!btn[0] || !btn[0].isConnected) {
					Button.clearCooldown(btn, true);
					return;
				}
				if (window.__bciAdrPaused || (typeof Events !== 'undefined' && Events._stackedModalPause)) {
					return;
				}
				remainingMs -= 50;
				saveAccum += 50;
				if (saveCd && saveAccum >= 500) {
					saveAccum = 0;
					var secsLeft = Math.max(0, (remainingMs / totalMs) * start);
					$SM.set(id, secsLeft, true);
				}
				if (remainingMs <= 0) {
					cdEl.css('width', '0%');
					Button.clearCooldown(btn, true);
					return;
				}
				cdEl.css('width', ((remainingMs / totalMs) * leftFrac * 100) + '%');
			}, 50);
			btn.data('countdown', tickId);
			btn.addClass('disabled');
			btn.data('onCooldown', true);
		}
	},

	clearCooldown: function(btn, cooldownEnded) {
		var ended = cooldownEnded || false;
		if(!ended){
			$('div.cooldown', btn).stop(true, true).css('width', '0%');
		}
		btn.data('onCooldown', false);
		if(btn.data('countdown')){
			window.clearInterval(btn.data('countdown'));
			$SM.remove('cooldown.'+ btn.attr('id'));
			btn.removeData('countdown');
		}
		if(!btn.data('disabled')) {
			btn.removeClass('disabled');
		}
	}
};
