;;; sidecar.el --- Emacs side of emacs-sidecar -*- lexical-binding: t; -*-

;; Loaded into the operator's Emacs by the sidecar, over its podless
;; connection; nothing goes in an init file.  It does three things:
;;
;;   - tells the sidecar what happens in org notebooks (org-mode buffers
;;     that visit a file): block runs and their results, edits, saves,
;;     reverts, where the operator is;
;;   - applies the edits of an agent attached to a buffer, one undo step
;;     each, and marks the text they wrote;
;;   - asks before a block the agent wrote or changed is run (the gate).
;;
;; The hooks and advice that report are removed when the last sidecar
;; disconnects.  The gate stays for as long as this file is loaded: text
;; an agent wrote stays marked, and asked about, until it is accepted.
;;
;; Events go out on the podless topic "sidecar", each a map with :event
;; and :time.  See doc/protocol.md.

;;; Code:

(require 'org)
(require 'org-element)
(require 'ob-core)
(require 'ob-comint)
(require 'ob-eval)
(require 'cl-lib)
(require 'subr-x)

(declare-function podless-publish "podless")
(declare-function podless-conn-id "podless")
(defvar podless-connection-cleanup-functions)

(defconst sidecar-version "0.1.0"
  "The version of emacs-sidecar this file belongs to.")

(defgroup sidecar nil
  "The Emacs side of emacs-sidecar."
  :group 'org)

(defface sidecar-agent
  '((t :inherit warning))
  "Face of the bar beside text an agent wrote that is not accepted yet."
  :group 'sidecar)

(defcustom sidecar-max-output (* 256 1024)
  "The most characters of one run's output sent to the sidecar.
A longer output keeps its first and last halves."
  :type 'integer
  :group 'sidecar)

(defvar sidecar-confirm-function #'sidecar--confirm
  "Called with BUFFER, BEG, END and WHAT to ask whether a block may run.
WHAT says why it is asked.  Returns non-nil for yes.")

;;;; ---------------------------------------------------------------- state

(defvar sidecar--clients nil
  "The podless connection ids of the sidecars attached to this Emacs.")

(defvar sidecar--attached (make-hash-table :test 'eq)
  "Base buffer to its attachment, a plist: :name, :causes, :timer.
Global, not buffer-local: `revert-buffer' kills buffer-locals.")

(defvar sidecar--run nil
  "The run in progress, a plist, while `org-babel-execute-src-block' runs.")

(defvar sidecar--pending-async nil
  "Async runs whose result has not arrived, as (MARKER . RUN).")

(defvar sidecar--in-async-filter nil
  "Non-nil while ob-comint inserts the result of an async run.")

(defvar sidecar--cause nil
  "Who is changing the buffer now: nil, the operator; `run'; or `agent'.")

(defvar sidecar--last-error nil
  "The error of the last failed run, so it is not reported twice.")

(defvar sidecar--reverting (make-hash-table :test 'eq :weakness 'key)
  "Buffer to the agent-written texts it held before a revert.")

(defvar sidecar--focus nil
  "The (FILE . LINE) last told as the operator's place.")

(defvar sidecar--focus-timer nil)

;;;; ---------------------------------------------------------------- publish

(defun sidecar--file (&optional buffer)
  "The file BUFFER visits, as a truename, or nil.  An indirect buffer has
its base buffer's."
  (let* ((buffer (or buffer (current-buffer)))
         (file (buffer-file-name (or (buffer-base-buffer buffer) buffer))))
    (and file (file-truename file))))

(defun sidecar--notebook-p (&optional buffer)
  "Whether BUFFER is an org-mode buffer visiting a file."
  (with-current-buffer (or buffer (current-buffer))
    (and (derived-mode-p 'org-mode) (sidecar--file) t)))

(defun sidecar--publish (event &rest fields)
  "Send EVENT with FIELDS, a plist, to every sidecar.
A broadcast: an event made while one sidecar's request is served is
still for all of them."
  (when sidecar--clients
    (condition-case err
        (podless-publish
         "sidecar"
         (append (list :event event
                       :time (format-time-string "%FT%T.%3N%z"))
                 fields)
         t)
      (error (message "sidecar: %s not sent: %s"
                      event (error-message-string err))))))

(defun sidecar--cap (text)
  "TEXT, cut to `sidecar-max-output' by dropping its middle."
  (if (or (null text) (<= (length text) sidecar-max-output))
      text
    (let ((half (/ sidecar-max-output 2)))
      (concat (substring text 0 half)
              (format "\n… %d characters left out …\n"
                      (- (length text) (* 2 half)))
              (substring text (- half))))))

;;;; ---------------------------------------------------------------- blocks

(defconst sidecar--runnable
  '(src-block inline-src-block babel-call inline-babel-call)
  "The element types `org-babel-execute-src-block' runs.")

(defun sidecar--block-region (pos)
  "The runnable element at POS as (TYPE BEG END ELEMENT), or nil.
BEG is before its #+name and #+header lines; END is after its last line,
before the blank lines and #+RESULTS that follow."
  (save-excursion
    (goto-char pos)
    (let* ((el (org-element-context))
           (type (org-element-type el)))
      (when (memq type sidecar--runnable)
        (list type
              (org-element-property :begin el)
              (save-excursion
                (goto-char (org-element-property :end el))
                (skip-chars-backward " \t\n")
                (point))
              el)))))

(defun sidecar--outline (pos)
  "The headings over POS, outermost first, as a vector; nil before the first."
  (let ((path (ignore-errors
                (save-excursion
                  (goto-char pos)
                  (org-get-outline-path t)))))
    (and path (vconcat (mapcar #'substring-no-properties path)))))

(defun sidecar--plain-result (text)
  "TEXT, what stands under #+RESULTS, without Org's wrapping.
The `: ' before each line of a short result and the #+begin_example
lines around a long one are Org's, not the block's output."
  (let ((lines (split-string text "\n")))
    (cond
     ((cl-every (lambda (l) (string-match-p "\\`[ \t]*\\(:\\( \\|\\'\\)\\|\\'\\)" l)) lines)
      (mapconcat (lambda (l) (replace-regexp-in-string "\\`[ \t]*: ?" "" l))
                 lines "\n"))
     ((string-match "\\`[ \t]*#\\+begin_example[^\n]*\n\\(\\(?:.\\|\n\\)*?\\)\n?[ \t]*#\\+end_example[ \t\n]*\\'"
                    text)
      (match-string 1 text))
     (t text))))

(defun sidecar--results-text (pos)
  "The output under #+RESULTS of the block at POS, or nil."
  (ignore-errors
    (save-excursion
      (goto-char pos)
      (when-let* ((where (org-babel-where-is-src-block-result)))
        (goto-char where)
        (forward-line 1)
        (sidecar--plain-result
         (buffer-substring-no-properties (point) (org-babel-result-end)))))))

(defun sidecar--new-id ()
  "A run id: 12 hex digits."
  (substring (md5 (format "%s%s%s" (float-time) (random) (emacs-pid))) 0 12))

;;;; ---------------------------------------------------------------- agent text
;;
;; Text an agent wrote carries an overlay with `sidecar-agent'.  A block
;; the agent touched is marked whole, so that deleting one word from a
;; block (`--dry-run', say) is seen as much as writing one.

(defun sidecar--agent-overlays (beg end)
  "The agent overlays that overlap BEG..END."
  (seq-filter (lambda (o) (overlay-get o 'sidecar-agent))
              (overlays-in beg end)))

(defun sidecar--any-agent-text-p ()
  "Whether the current buffer holds agent text that is not accepted."
  (save-restriction
    (widen)
    (and (sidecar--agent-overlays (point-min) (point-max)) t)))

;; The bar beside agent text is drawn a line at a time, by an overlay on
;; the line's first character.  `line-prefix' on the agent overlay would
;; draw it too, but hides the `line-prefix' of `org-indent-mode', a text
;; property, and with it the indentation.  On a graphic frame the bar is
;; in the fringe; a terminal has none, and shows it before the text.

(when (fboundp 'define-fringe-bitmap)
  (define-fringe-bitmap 'sidecar-bar [#b11100000] nil nil '(center t)))

(defconst sidecar--bar
  (propertize "▎" 'face 'sidecar-agent
              'display '(when (display-graphic-p)
                          left-fringe sidecar-bar sidecar-agent))
  "The bar of a line the agent wrote.")

(defun sidecar--bars (beg end)
  "Draw again the bars of the lines of BEG..END."
  (save-excursion
    (save-restriction
      (widen)
      (let ((b (progn (goto-char (min beg (point-max)))
                      (line-beginning-position)))
            (e (progn (goto-char (min end (point-max)))
                      (min (1+ (line-end-position)) (point-max)))))
        (dolist (o (overlays-in b e))
          (when (overlay-get o 'sidecar-bar)
            (delete-overlay o)))
        (goto-char b)
        (while (< (point) e)
          (when (sidecar--agent-overlays
                 (point) (min (1+ (line-end-position)) (point-max)))
            ;; on the first character, and moving with it
            (let ((o (make-overlay (point) (1+ (point)) nil t nil)))
              (overlay-put o 'sidecar-bar t)
              (overlay-put o 'evaporate t)
              (overlay-put o 'before-string sidecar--bar)))
          (forward-line 1))))))

(defun sidecar--on-agent-change (_overlay after beg end &optional _len)
  "A modification hook of the agent overlays: the lines may have changed."
  (when after
    (sidecar--bars beg end)))

(defun sidecar--overlay (beg end)
  "Mark BEG..END as written by the agent."
  (when (< beg end)
    (let ((o (make-overlay beg end nil t nil)))
      (overlay-put o 'sidecar-agent t)
      (overlay-put o 'evaporate t)
      (overlay-put o 'modification-hooks '(sidecar--on-agent-change))
      (overlay-put o 'help-echo "Written by the agent; M-x sidecar-accept")
      (sidecar--bars beg end)
      o)))

(defun sidecar--blocks-touching (beg end)
  "The regions (BEG . END) of the runnable elements that overlap BEG..END."
  (let ((case-fold-search t)
        (points (list beg end))
        regions)
    (save-excursion
      (goto-char beg)
      (while (re-search-forward "^[ \t]*#\\+\\(begin_src\\|call:\\)" end t)
        (push (match-beginning 0) points)))
    (dolist (pos points)
      (pcase (ignore-errors (sidecar--block-region pos))
        (`(,_ ,b ,e ,_)
         (when (and (<= b end) (>= e beg))
           (cl-pushnew (cons b e) regions :test #'equal)))))
    regions))

(defun sidecar--mark (beg end)
  "Mark what an edit of BEG..END wrote: the text, and each block it touched."
  (sidecar--overlay beg end)
  (pcase-dolist (`(,b . ,e) (sidecar--blocks-touching beg end))
    (unless (cl-some (lambda (o) (and (<= (overlay-start o) b)
                                      (>= (overlay-end o) e)))
                     (sidecar--agent-overlays b e))
      (sidecar--overlay b e)))
  (force-mode-line-update))

(defun sidecar--clear (beg end)
  "Accept BEG..END: no agent overlay covers it afterwards.
An overlay that reaches past it keeps what is outside."
  (dolist (o (sidecar--agent-overlays beg end))
    (let ((ob (overlay-start o))
          (oe (overlay-end o)))
      (cond ((and (>= ob beg) (<= oe end)) (delete-overlay o))
            ((< ob beg)
             (move-overlay o ob beg)
             (when (> oe end) (sidecar--overlay end oe)))
            (t (move-overlay o end oe)))))
  (sidecar--bars beg end)
  (force-mode-line-update))

(defun sidecar-accept (beg end)
  "Accept the agent's text in the region, or the block or text at point.
Accepted text is no longer asked about before it runs."
  (interactive
   (cond ((use-region-p) (list (region-beginning) (region-end)))
         ((sidecar--block-region (point))
          (pcase-let ((`(,_ ,b ,e ,_) (sidecar--block-region (point))))
            (list b e)))
         (t (let ((os (sidecar--agent-overlays (point) (1+ (point)))))
              (if os
                  (list (apply #'min (mapcar #'overlay-start os))
                        (apply #'max (mapcar #'overlay-end os)))
                (user-error "No agent text here"))))))
  (sidecar--clear beg end)
  (when (sidecar--notebook-p)
    (sidecar--publish "gate" :decision "accepted" :file (sidecar--file)
                      :line (line-number-at-pos beg t))))

(defun sidecar-accept-buffer ()
  "Accept all of the agent's text in this buffer."
  (interactive)
  (save-restriction
    (widen)
    (sidecar-accept (point-min) (point-max))))

(defun sidecar-next-agent-text ()
  "Move to the next text the agent wrote that is not accepted."
  (interactive)
  (let ((pos (next-single-char-property-change (point) 'sidecar-agent)))
    (unless (get-char-property pos 'sidecar-agent)
      (setq pos (next-single-char-property-change pos 'sidecar-agent)))
    (if (< pos (point-max))
        (goto-char pos)
      (user-error "No more agent text"))))

;; A revert replaces the buffer's text, and overlays with it.  The texts
;; are kept across it and marked again where they still stand.

(defun sidecar--before-revert ()
  (when (sidecar--any-agent-text-p)
    (save-restriction
      (widen)
      (puthash (current-buffer)
               (delete-dups
                (mapcar (lambda (o)
                          (buffer-substring-no-properties
                           (overlay-start o) (overlay-end o)))
                        (sidecar--agent-overlays (point-min) (point-max))))
               sidecar--reverting))))

(defun sidecar--after-revert ()
  (when-let* ((texts (gethash (current-buffer) sidecar--reverting)))
    (remhash (current-buffer) sidecar--reverting)
    (save-excursion
      (save-restriction
        (widen)
        (remove-overlays (point-min) (point-max) 'sidecar-agent t)
        (remove-overlays (point-min) (point-max) 'sidecar-bar t)
        (dolist (text texts)
          (goto-char (point-min))
          (when (search-forward text nil t)
            (sidecar--overlay (match-beginning 0) (match-end 0)))))))
  (sidecar--on-revert))

(add-hook 'before-revert-hook #'sidecar--before-revert)
(add-hook 'after-revert-hook #'sidecar--after-revert)

;;;; ---------------------------------------------------------------- refusals
;;
;; Org evaluates Emacs Lisp it finds in header arguments, and in table
;; formulas, before any question is asked.  Emacs evaluates file-local
;; variables on the next visit.  An agent's edit may not write either.

(defconst sidecar--header-line
  "^[ \t]*\\(?:#\\+\\(?:begin_src\\|headers?:\\|property:\\|call:\\|tblfm:\\)\\|:header-args[^ \t\n]*:\\)"
  "A line whose arguments Org may evaluate.")

(defconst sidecar--inline-elisp
  (concat "src_[^ \t\n[{]+\\[[^]\n]*[ \t=][(`']"
          "\\|call_[^ \t\n[(]+\\(?:\\[[^]\n]*\\]\\)?([^)\n]*=[ \t]*[(`']"
          "\\|call_[^ \t\n[(]+[^ \t\n]*\\[[^]\n]*[ \t=][(`']")
  "An inline block or call with Emacs Lisp in its arguments.")

(defun sidecar--elisp-refusal (text)
  "Why TEXT, whole lines, would have Org evaluate Emacs Lisp; nil if not."
  (let ((case-fold-search t)
        why)
    (dolist (line (split-string text "\n"))
      (cond
       (why)
       ((and (string-match-p sidecar--header-line line)
             (string-match-p "[ \t=,[(][(`']" line))
        (setq why (format "Emacs Lisp in the arguments of `%s': Org \
evaluates it before asking" (string-trim line))))
       ((string-match-p sidecar--inline-elisp line)
        (setq why (format "Emacs Lisp in the arguments of an inline block \
or call in `%s'" (string-trim line))))))
    why))

(defun sidecar--refusal (text)
  "Why TEXT, whole lines, may not be written by an agent; nil if it may."
  (let ((case-fold-search t))
    (cond
     ((string-match-p "Local Variables:" text)
      "a `Local Variables:' line: Emacs would evaluate it")
     ((string-match-p "^[ \t]*#\\+setupfile:" text)
      "#+SETUPFILE: Org would load it")
     (t (sidecar--elisp-refusal text)))))

(defun sidecar--first-lines-refusal (pos text)
  "Why TEXT may not be written at POS, because of the file's first lines."
  (and (string-search "-*-" text)
       (<= (line-number-at-pos pos t) 2)
       "`-*-' on the file's first lines: Emacs would read variables from it"))

;;;; ---------------------------------------------------------------- the gate

(defun sidecar--confirm (buffer beg end what)
  "Show BUFFER's BEG..END and ask whether to run it.  WHAT says why."
  (let ((text (with-current-buffer buffer (buffer-substring beg end)))
        (review (get-buffer-create "*sidecar review*")))
    (with-current-buffer review
      (let ((inhibit-read-only t))
        (erase-buffer)
        (insert (propertize what 'face 'warning) "\n\n" text "\n")
        (goto-char (point-min))
        (setq buffer-read-only t)))
    (save-window-excursion
      (display-buffer review '(display-buffer-at-bottom
                               (window-height . fit-window-to-buffer)))
      (y-or-n-p "Run this block? "))))

(defun sidecar--gate (pos)
  "Ask before the block at POS runs, if an agent wrote or changed it.
Signals a `user-error' when the operator says no, or when the block may
not run at all.  Does nothing in a buffer without agent text."
  (when (and pos (sidecar--any-agent-text-p))
    (pcase (sidecar--block-region pos)
      (`(,type ,beg ,end ,el)
       (let* ((regions (list (cons beg end)))
              (callee (and (memq type '(babel-call inline-babel-call))
                           (org-element-property :call el)))
              (callee-pos (and callee (org-babel-find-named-block callee)))
              (_ (when callee-pos
                   (pcase (sidecar--block-region callee-pos)
                     (`(,_ ,b ,e ,_) (push (cons b e) regions)))))
              (touched (cl-some (lambda (r)
                                  (sidecar--agent-overlays (car r) (cdr r)))
                                regions))
              (noweb (and (not touched)
                          (string-match-p
                           "<<[^ \t\n][^\n]*>>"
                           (or (org-element-property :value el) ""))))
              (line (line-number-at-pos beg t))
              (file (sidecar--file)))
         (when (or touched noweb)
           (when-let* ((refusal (sidecar--elisp-refusal
                                 (mapconcat (lambda (r)
                                              (buffer-substring-no-properties
                                               (car r) (cdr r)))
                                            regions "\n"))))
             (sidecar--publish "gate" :decision "refused" :file file
                               :line line :reason refusal)
             (user-error "sidecar: not run: %s" refusal))
           (if (funcall sidecar-confirm-function (current-buffer) beg end
                        (if touched
                            "The agent wrote or changed this block, and you \
have not run it since."
                          "This block has noweb references, and the buffer \
holds agent text you have not accepted."))
               (progn
                 (when touched
                   (pcase-dolist (`(,b . ,e) regions) (sidecar--clear b e)))
                 (sidecar--publish "gate" :decision "approved" :file file
                                   :line line))
             (sidecar--publish "gate" :decision "declined" :file file
                               :line line)
             (user-error "sidecar: not run"))))))))

;;;; ---------------------------------------------------------------- runs

(defun sidecar--run-fields (run)
  "The fields every event of RUN carries."
  (list :id (plist-get run :id)
        :file (plist-get run :file)
        :buffer (plist-get run :buffer)
        :line (plist-get run :line)
        :name (plist-get run :name)
        :lang (plist-get run :lang)
        :outline (plist-get run :outline)
        :parent (plist-get run :parent)))

(defun sidecar--run-end (run status &rest fields)
  "Tell that RUN ended with STATUS, and FIELDS."
  (apply #'sidecar--publish "run"
         (append (list :phase "end" :status status
                       :ms (round (* 1000 (- (float-time)
                                             (plist-get run :start)))))
                 (sidecar--run-fields run)
                 fields)))

(defun sidecar--start-run (pos info)
  "Tell that the block at POS, with org-babel INFO or nil, starts; return the run."
  (let* ((region (ignore-errors (sidecar--block-region pos)))
         (el (nth 3 region))
         (run (list :id (sidecar--new-id)
                    :start (float-time)
                    :pos pos
                    :file (sidecar--file)
                    :buffer (buffer-name)
                    :line (line-number-at-pos pos t)
                    :name (or (nth 4 info)
                              (and el (org-element-property :name el)))
                    :lang (or (nth 0 info)
                              (and el (org-element-property :language el)))
                    :outline (sidecar--outline pos)
                    :parent (plist-get sidecar--run :id))))
    (apply #'sidecar--publish "run"
           (append (list :phase "start" :status "running"
                         :body (sidecar--cap
                                (or (nth 1 info)
                                    (and el (org-element-property :value el))
                                    (and el (org-element-property :call el)))))
                   (sidecar--run-fields run)))
    run))

(defun sidecar--around-execute (orig &rest args)
  "Around `org-babel-execute-src-block': the gate, then the run's events."
  (let* ((info (nth 1 args))
         (pos (or (nth 5 info)
                  (ignore-errors (org-babel-where-is-src-block-head))
                  org-babel-current-src-block-location)))
    (sidecar--gate pos)
    (if (not (and sidecar--clients pos (sidecar--notebook-p)))
        (apply orig args)
      (let* ((sidecar--run (sidecar--start-run pos info))
             (value
              (condition-case err
                  (apply orig args)
                (quit
                 (sidecar--run-end sidecar--run "cancelled")
                 (signal (car err) (cdr err)))
                (error
                 (setq sidecar--last-error err)
                 (sidecar--run-end sidecar--run "error"
                                   :error (error-message-string err)
                                   :exit (plist-get sidecar--run :exit)
                                   :stderr (sidecar--cap
                                            (plist-get sidecar--run :stderr)))
                 (signal (car err) (cdr err)))))
             (run sidecar--run))
        (if (plist-get run :async)
            (progn
              (push (cons (copy-marker pos) run) sidecar--pending-async)
              (apply #'sidecar--publish "run"
                     (append (list :phase "async" :status "running")
                             (sidecar--run-fields run))))
          (let ((exit (plist-get run :exit)))
            (sidecar--run-end
             run
             (if (and exit (not (eql exit 0))) "error" "done")
             :exit exit
             :stderr (sidecar--cap (plist-get run :stderr))
             :output (sidecar--cap
                      (or (and (plist-get run :inserted)
                               (sidecar--results-text pos))
                          (and value (format "%s" value)))))))
        value))))

(defun sidecar--before-async-register (&rest _)
  "Before `org-babel-comint-async-register': the run in progress is async."
  (when sidecar--run
    (setq sidecar--run (plist-put sidecar--run :async t))))

(defun sidecar--before-error-notify (exit-code stderr)
  "Before `org-babel-eval-error-notify': keep EXIT-CODE and STDERR."
  (when sidecar--run
    (setq sidecar--run (plist-put sidecar--run :exit exit-code))
    (setq sidecar--run (plist-put sidecar--run :stderr stderr))))

(defun sidecar--around-async-filter (orig &rest args)
  "Around `org-babel-comint-async-filter': results it inserts are async."
  (let ((sidecar--in-async-filter t)
        (sidecar--run nil))
    (apply orig args)))

(defun sidecar--pending-here ()
  "The async run waiting on the block at point, removed from the list."
  (let* ((head (ignore-errors (org-babel-where-is-src-block-head)))
         (entry (and head
                     (cl-find-if (lambda (e)
                                   (and (eq (marker-buffer (car e))
                                            (current-buffer))
                                        (= (car e) head)))
                                 ;; the oldest first
                                 (reverse sidecar--pending-async)))))
    (setq sidecar--pending-async
          (cl-remove-if (lambda (e)
                          (or (eq e entry)
                              (not (buffer-live-p (marker-buffer (car e))))))
                        sidecar--pending-async))
    entry))

(defun sidecar--around-insert-result (orig &rest args)
  "Around `org-babel-insert-result': the change is the run's, not the
operator's, and an async run ends here."
  (let ((pending (and sidecar--in-async-filter (sidecar--pending-here))))
    (prog1 (let ((sidecar--cause 'run))
             (apply orig args))
      (when sidecar--run
        (setq sidecar--run (plist-put sidecar--run :inserted t)))
      (when pending
        (sidecar--run-end (cdr pending) "done"
                          :async t
                          :output (sidecar--cap
                                   (sidecar--results-text (car pending))))
        (set-marker (car pending) nil)))))

;;;; ---------------------------------------------------------------- buffers

(defun sidecar--buffer-event (event &rest fields)
  "Tell EVENT about the current buffer, when it is a notebook."
  (when (and sidecar--clients (sidecar--notebook-p))
    (apply #'sidecar--publish event
           :file (sidecar--file) :buffer (buffer-name)
           :modified (and (buffer-modified-p) t)
           fields)))

(defun sidecar--on-first-change ()
  (when (and sidecar--clients (not sidecar--cause) (sidecar--notebook-p))
    (sidecar--buffer-event "modify" :line (line-number-at-pos (point) t)
                           :outline (sidecar--outline (point)))))

(defun sidecar--on-save () (sidecar--buffer-event "save"))
(defun sidecar--on-revert () (sidecar--buffer-event "revert"))
(defun sidecar--on-open () (sidecar--buffer-event "open"))

(defun sidecar--on-kill ()
  (when (sidecar--notebook-p)
    (sidecar--buffer-event "kill")
    (let ((base (or (buffer-base-buffer) (current-buffer))))
      (when (and (eq base (current-buffer)) (gethash base sidecar--attached))
        (sidecar--detach-buffer base)))))

(defun sidecar--on-focus ()
  "Tell where the operator is, when that is a notebook and has changed."
  (when sidecar--clients
    (let ((buffer (window-buffer (selected-window))))
      (when (sidecar--notebook-p buffer)
        (with-current-buffer buffer
          (let ((place (cons (sidecar--file)
                             (line-number-at-pos (window-point) t))))
            (unless (equal place sidecar--focus)
              (setq sidecar--focus place)
              (sidecar--publish "focus" :file (car place) :line (cdr place)
                                :outline (sidecar--outline (window-point))))))))))

(defun sidecar--on-warning (type message &optional level &rest _)
  "Before `display-warning': tell the sidecar."
  (when sidecar--clients
    (sidecar--publish "problem" :kind "warning"
                      :level (format "%s" (or level :warning))
                      :source (format "%s" type)
                      :message (format "%s" message))))

(defconst sidecar--quiet-errors
  '(quit beginning-of-buffer end-of-buffer beginning-of-line end-of-line
    buffer-read-only text-read-only minibuffer-quit user-error)
  "Signals that are not worth a line in the console.")

(defun sidecar--on-command-error (data _context _caller)
  "Before `command-error-function': tell of an error in a notebook."
  (when (and sidecar--clients
             (not (equal data sidecar--last-error))
             (not (memq (car-safe data) sidecar--quiet-errors))
             (sidecar--notebook-p))
    (sidecar--publish "problem" :kind "error"
                      :file (sidecar--file)
                      :line (line-number-at-pos (point) t)
                      :source (format "%s" (car-safe data))
                      :message (error-message-string data)))
  (setq sidecar--last-error nil))

(defun sidecar-buffers ()
  "The notebooks open in this Emacs, as a vector of maps."
  (vconcat
   (delq nil
         (mapcar
          (lambda (buffer)
            (when (and (not (buffer-base-buffer buffer))
                       (sidecar--notebook-p buffer))
              (with-current-buffer buffer
                (list :file (sidecar--file)
                      :buffer (buffer-name)
                      :modified (and (buffer-modified-p) t)
                      :attached (plist-get (gethash buffer sidecar--attached)
                                           :name)
                      :agent-text (sidecar--any-agent-text-p)))))
          (buffer-list)))))

(defun sidecar--buffer (file)
  "The buffer visiting FILE, or nil."
  (or (find-buffer-visiting file)
      (get-file-buffer file)))

(defun sidecar-text (file)
  "The text Emacs holds for FILE, unsaved edits included, or nil."
  (when-let* ((buffer (sidecar--buffer file)))
    (with-current-buffer buffer
      (save-restriction
        (widen)
        (list :text (buffer-substring-no-properties (point-min) (point-max))
              :tick (buffer-chars-modified-tick)
              :modified (and (buffer-modified-p) t))))))

(defun sidecar-visit (file &optional line)
  "Show FILE in the selected window, at LINE."
  (let ((buffer (find-file-noselect file)))
    (pop-to-buffer buffer '((display-buffer-reuse-window
                             display-buffer-same-window)))
    (when line
      (widen)
      (goto-char (point-min))
      (forward-line (1- line))
      (when (derived-mode-p 'org-mode)
        (ignore-errors (org-reveal)))
      (ignore-errors (recenter)))
    (when (display-graphic-p)
      (ignore-errors (select-frame-set-input-focus (selected-frame))))
    t))

;;;; ---------------------------------------------------------------- attachments
;;
;; An attached buffer's text goes to the sidecar soon after each change,
;; with who made the changes, and the sidecar may apply an agent's edits
;; to it.

(defun sidecar--mode-line ()
  "What the mode line says in an attached buffer, or one with agent text."
  (let ((att (gethash (or (buffer-base-buffer) (current-buffer))
                      sidecar--attached))
        (pending (and (derived-mode-p 'org-mode) (sidecar--any-agent-text-p))))
    (when (or att pending)
      (concat " "
              (propertize (if att
                              (format "agent:%s" (plist-get att :name))
                            "agent text")
                          'face (if pending 'warning 'success))
              (if pending "!" "")))))

(defconst sidecar--mode-line-entry '(:eval (sidecar--mode-line)))

(unless (member sidecar--mode-line-entry mode-line-misc-info)
  (setq mode-line-misc-info
        (append mode-line-misc-info (list sidecar--mode-line-entry))))

(defun sidecar--send-text (buffer)
  "Send BUFFER's text to the sidecar, with the causes of its changes."
  (when (buffer-live-p buffer)
    (when-let* ((att (gethash buffer sidecar--attached)))
      (with-current-buffer buffer
        (let ((causes (or (plist-get att :causes) '(operator)))
              (timer (plist-get att :timer)))
          (when (timerp timer) (cancel-timer timer))
          (puthash buffer (plist-put (plist-put att :causes nil) :timer nil)
                   sidecar--attached)
          (save-restriction
            (widen)
            (sidecar--publish
             "text"
             :file (sidecar--file)
             :name (plist-get att :name)
             :tick (buffer-chars-modified-tick)
             :modified (and (buffer-modified-p) t)
             :causes (vconcat (mapcar #'symbol-name causes))
             :text (buffer-substring-no-properties (point-min)
                                                   (point-max)))))))))

(defun sidecar--after-change (&rest _)
  "Note who changed an attached buffer, and send its text shortly."
  (let* ((buffer (or (buffer-base-buffer) (current-buffer)))
         (att (gethash buffer sidecar--attached)))
    (when att
      (let ((causes (plist-get att :causes))
            (cause (or sidecar--cause 'operator)))
        (unless (memq cause causes)
          (setq att (plist-put att :causes (cons cause causes))))
        (unless (plist-get att :timer)
          (setq att (plist-put att :timer
                               (run-with-timer 0.3 nil #'sidecar--send-text
                                               buffer))))
        (puthash buffer att sidecar--attached)))))

(defun sidecar-attach (file name)
  "Attach the buffer visiting FILE to the agent NAME; return its text.
The file is opened if no buffer visits it."
  (let ((buffer (or (sidecar--buffer file) (find-file-noselect file))))
    (with-current-buffer buffer
      (unless (derived-mode-p 'org-mode)
        (error "sidecar: %s is not an org buffer" file))
      (let ((other (gethash buffer sidecar--attached)))
        (when (and other (not (equal (plist-get other :name) name)))
          (error "sidecar: %s is attached to %s" file (plist-get other :name))))
      (puthash buffer (list :name name :causes nil :timer nil)
               sidecar--attached)
      (add-hook 'after-change-functions #'sidecar--after-change)
      (force-mode-line-update)
      (sidecar-text file))))

(defun sidecar--detach-buffer (buffer)
  (when-let* ((att (gethash buffer sidecar--attached)))
    (when (timerp (plist-get att :timer))
      (cancel-timer (plist-get att :timer)))
    (remhash buffer sidecar--attached)
    (when (zerop (hash-table-count sidecar--attached))
      (remove-hook 'after-change-functions #'sidecar--after-change))
    (force-mode-line-update t)))

(defun sidecar-detach (file)
  "Detach the buffer visiting FILE.  Agent text stays marked."
  (when-let* ((buffer (sidecar--buffer file)))
    (sidecar--detach-buffer buffer)
    t))

;;;; ---------------------------------------------------------------- edits

(defun sidecar--count (text)
  "How many times TEXT occurs in the buffer, without overlap."
  (save-excursion
    (goto-char (point-min))
    (let ((n 0))
      (while (search-forward text nil t) (cl-incf n))
      n)))

(defun sidecar--subtree-end (path)
  "The end of the subtree whose outline path is PATH, a list of headings."
  (let ((pos (condition-case nil
                 (org-find-olp path t)
               (error nil))))
    (unless pos
      (error "no heading %s; the headings are read from the notebook as it is now"
             (mapconcat #'identity path " / ")))
    (save-excursion
      (goto-char pos)
      (org-end-of-subtree t t)
      (point))))

(defun sidecar--whole-lines (beg end new)
  "The lines BEG..END would be part of once it holds NEW."
  (concat (buffer-substring-no-properties
           (save-excursion (goto-char beg) (line-beginning-position)) beg)
          new
          (buffer-substring-no-properties
           end (save-excursion (goto-char end) (line-end-position)))))

(defun sidecar--write (beg end new)
  "Replace BEG..END with NEW as the agent; return where NEW starts.
Signals, changing nothing, if NEW may not be written."
  (when-let* ((why (or (sidecar--refusal (sidecar--whole-lines beg end new))
                       (sidecar--first-lines-refusal beg new))))
    (error "refused: %s" why))
  (let ((sidecar--cause 'agent))
    (goto-char beg)
    (delete-region beg end)
    (insert new)
    (sidecar--mark beg (point))
    beg))

(defun sidecar--replace (old new)
  "Replace the one occurrence of OLD with NEW; return where the change starts.
Only what differs between them is written."
  (when (string-empty-p old)
    (error "the old text is empty"))
  (pcase (sidecar--count old)
    (0 (error "the old text is not in the notebook; it may have changed, \
read it again"))
    (1 nil)
    (n (error "the old text occurs %d times; give more of the text around it" n)))
  (goto-char (point-min))
  (search-forward old)
  (let* ((start (match-beginning 0))
         (prefix (let ((d (compare-strings old nil nil new nil nil)))
                   (if (eq d t) (length old) (1- (abs d)))))
         (max-suffix (- (min (length old) (length new)) prefix))
         (suffix 0))
    (while (and (< suffix max-suffix)
                (eq (aref old (- (length old) suffix 1))
                    (aref new (- (length new) suffix 1))))
      (cl-incf suffix))
    (sidecar--write (+ start prefix)
                    (+ start (- (length old) suffix))
                    (substring new prefix (- (length new) suffix)))))

(defun sidecar--append (path text)
  "Add TEXT at the end of the subtree PATH, or of the buffer when PATH is nil."
  (let* ((pos (if path (sidecar--subtree-end path) (point-max)))
         (text (concat (if (string-suffix-p "\n" text) text (concat text "\n")))))
    (goto-char pos)
    ;; onto a line of its own, one blank line after what is there
    (skip-chars-backward " \t\n")
    (let ((at (point))
          (gap (if (bobp) "" "\n\n")))
      (sidecar--write at pos
                      (concat gap text (if (< pos (point-max)) "\n" "")))
      ;; where the text itself starts, after the blank line before it
      (+ at (length gap)))))

(defun sidecar-apply-edit (file edit)
  "Apply EDIT, a map from an agent, to the attached buffer visiting FILE.
EDIT has :op, \"replace\" (with :old and :new) or \"append\" (with :text
and maybe :under, a vector of headings).  Returns a map with :line, where
the change starts.  One undo undoes it."
  (let ((buffer (sidecar--buffer file)))
    (unless (and buffer (gethash buffer sidecar--attached))
      (error "the notebook is not attached"))
    (with-current-buffer buffer
      (when buffer-read-only
        (error "the notebook is read-only"))
      (let ((op (gethash :op edit))
            start)
        (save-excursion
          (save-restriction
            (widen)
            (setq start
                  (pcase op
                    ("replace" (sidecar--replace (gethash :old edit)
                                                 (gethash :new edit)))
                    ("append" (sidecar--append
                               (append (gethash :under edit) nil)
                               (gethash :text edit)))
                    (_ (error "unknown edit %S" op))))
            (setq start (line-number-at-pos start t))))
        (sidecar--send-text buffer)
        (list :line start
              :tick (buffer-chars-modified-tick))))))

;;;; ---------------------------------------------------------------- install

(advice-add 'org-babel-execute-src-block :around #'sidecar--around-execute)

(defun sidecar--hooks (on)
  "Start or stop reporting to the sidecars."
  (if on
      (progn
        (advice-add 'org-babel-comint-async-register :before
                    #'sidecar--before-async-register)
        (advice-add 'org-babel-eval-error-notify :before
                    #'sidecar--before-error-notify)
        (advice-add 'org-babel-comint-async-filter :around
                    #'sidecar--around-async-filter)
        (advice-add 'org-babel-insert-result :around
                    #'sidecar--around-insert-result)
        (advice-add 'display-warning :before #'sidecar--on-warning)
        (add-function :before command-error-function
                      #'sidecar--on-command-error)
        (add-hook 'first-change-hook #'sidecar--on-first-change)
        (add-hook 'after-save-hook #'sidecar--on-save)
        (add-hook 'find-file-hook #'sidecar--on-open)
        (add-hook 'kill-buffer-hook #'sidecar--on-kill)
        (unless sidecar--focus-timer
          (setq sidecar--focus-timer
                (run-with-idle-timer 0.4 t #'sidecar--on-focus))))
    (advice-remove 'org-babel-comint-async-register
                   #'sidecar--before-async-register)
    (advice-remove 'org-babel-eval-error-notify
                   #'sidecar--before-error-notify)
    (advice-remove 'org-babel-comint-async-filter
                   #'sidecar--around-async-filter)
    (advice-remove 'org-babel-insert-result #'sidecar--around-insert-result)
    (advice-remove 'display-warning #'sidecar--on-warning)
    (remove-function command-error-function #'sidecar--on-command-error)
    (remove-hook 'first-change-hook #'sidecar--on-first-change)
    (remove-hook 'after-save-hook #'sidecar--on-save)
    (remove-hook 'find-file-hook #'sidecar--on-open)
    (remove-hook 'kill-buffer-hook #'sidecar--on-kill)
    (when sidecar--focus-timer
      (cancel-timer sidecar--focus-timer)
      (setq sidecar--focus-timer nil))
    (setq sidecar--focus nil)
    ;; nobody is left to apply an agent's edits
    (maphash (lambda (buffer _) (sidecar--detach-buffer buffer))
             (copy-hash-table sidecar--attached))))

(defun sidecar--cleanup (conn)
  "A podless cleanup function: the sidecar on CONN is gone."
  (setq sidecar--clients (delete (podless-conn-id conn) sidecar--clients))
  (unless sidecar--clients
    (sidecar--hooks nil)))

(add-hook 'podless-connection-cleanup-functions #'sidecar--cleanup)

(defun sidecar-install (client)
  "Start reporting to the sidecar on the podless connection CLIENT, its id.
Returns a map about this Emacs."
  (cl-pushnew client sidecar--clients :test #'equal)
  (sidecar--hooks t)
  (list :version sidecar-version
        :emacs emacs-version
        :org (org-version)
        :devops (and (featurep 'devops) t)
        :server (bound-and-true-p server-name)))

(provide 'sidecar)
;;; sidecar.el ends here
