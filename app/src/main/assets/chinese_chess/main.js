// 主程序入口（Android 离线资源）

import { ChineseChess } from './chess.js';
import { ChessAI } from './ai.js';
import { BoardRenderer, GameInfoDisplay, GameOverModal } from './ui.js';
import { AudioManager } from './audio.js';
import {
    boardScanCandidates,
    operationScanCandidates,
    ScanCursor,
    ScanPhase
} from './scan.js';

function syncChessViewportHeight() {
    document.documentElement.style.setProperty(
        '--chess-viewport-height',
        `${Math.max(320, window.innerHeight)}px`
    );
}

syncChessViewportHeight();
window.addEventListener('resize', syncChessViewportHeight);

/**
 * 游戏控制器
 */
class GameController {
    constructor() {
        this.chess = new ChineseChess();
        this.ai = new ChessAI(this.chess);
        this.renderer = new BoardRenderer('chessboard');
        this.infoDisplay = new GameInfoDisplay();
        this.gameOverModal = new GameOverModal();
        this.audioManager = new AudioManager();
        this.scanCursor = new ScanCursor();
        this.scanIntervalMs = 1500;
        this.scanTimer = null;
        this.selectedColumn = null;
        this.navigationLevel = ScanPhase.COLUMN;
        this.pendingConfirmedAction = null;
        this.boardScanSnapshot = null;
        
        this.isAIThinking = false;
        this.gameGeneration = 0;
        this.timedMode = false;
        this.redTime = 900; // 15分钟
        this.blackTime = 900;
        this.timerInterval = null;
        
        this.init();
    }

    /**
     * 初始化游戏
     */
    init() {
        // 设置渲染器回调
        this.renderer.onPieceClick = (x, y, piece) => this.handlePieceClick(x, y, piece);
        this.renderer.onMoveClick = (x, y) => this.handleMoveClick(x, y);
        
        // 设置按钮事件
        document.getElementById('newGameBtn').addEventListener('click', () => this.newGame());
        document.getElementById('undoBtn').addEventListener('click', () => this.undoMove());
        document.getElementById('soundBtn').addEventListener('click', () => {
            this.toggleSound();
            this.startColumnScan();
        });
        document.getElementById('exitBtn').addEventListener('click', () => this.requestExit());
        document.getElementById('returnSelectionBtn').addEventListener('click', event => {
            if (this.canScan() && !event.currentTarget.disabled) this.returnToBoardSelection();
        });
        document.getElementById('returnColumnBtn').addEventListener('click', event => {
            if (this.canScan() && !event.currentTarget.disabled) this.startColumnScan();
        });
        document.getElementById('returnRowBtn').addEventListener('click', event => {
            if (this.canScan() && !event.currentTarget.disabled) this.startRowScan(this.selectedColumn);
        });
        document.getElementById('confirmActionBtn')
            .addEventListener('click', () => this.confirmPendingAction());
        document.getElementById('cancelActionBtn')
            .addEventListener('click', () => this.cancelPendingAction());
        
        // 设置游戏结束弹窗
        this.gameOverModal.onClose(() => {
            this.gameOverModal.hide();
            this.newGame();
        });
        
        // 初始渲染
        this.updateDisplay();
        this.startTimer();
        this.startColumnScan();
    }

    /**
     * 处理棋子点击
     */
    handlePieceClick(x, y, piece) {
        if (this.chess.gameOver || this.isAIThinking) return;
        
        // 如果不是当前玩家的棋子，检查是否可以吃子
        if (!this.chess.isCurrentPlayerPiece(piece)) {
            if (this.renderer.selectedPiece) {
                const legalMoves = this.renderer.legalMoves;
                const targetMove = legalMoves.find(m => m.x === x && m.y === y);
                if (targetMove) {
                    this.handleMoveClick(x, y);
                }
            }
            return;
        }
        
        // 触摸随时覆盖耳机扫描位置，直接进入该棋子的落点扫描。
        this.audioManager.playSelectSound();
        this.renderer.setSelectedPiece(x, y);
        const legalMoves = this.chess.getLegalMoves(x, y);
        this.renderer.setLegalMoves(legalMoves);
        this.updateDisplay();
        this.startTargetScan(x, y);
    }

    /**
     * 处理移动点击
     */
    async handleMoveClick(toX, toY) {
        if (!this.renderer.selectedPiece || this.chess.gameOver || this.isAIThinking) return;
        this.pauseBciScan('等待 AI 落子');
        
        const fromX = this.renderer.selectedPiece.x;
        const fromY = this.renderer.selectedPiece.y;
        
        // 执行移动
        const moveResult = this.chess.makeMove(fromX, fromY, toX, toY);
        
        // 播放音效
        if (moveResult.captured) {
            this.audioManager.playCaptureSound();
        } else {
            this.audioManager.playMoveSound();
        }
        
        // 更新显示
        this.renderer.setLastMove(moveResult);
        this.renderer.clearSelection();
        
        const moveNumber = Math.floor(this.chess.moveHistory.length / 2);
        this.infoDisplay.addMoveToHistory(moveNumber, this.formatMove(moveResult), moveResult.isRed);
        
        this.updateDisplay();
        
        // 检查游戏是否结束
        if (this.chess.gameOver) {
            this.handleGameOver();
            return;
        }
        
        // AI 回合
        if (this.chess.currentPlayer === 'black') {
            await this.aiMove();
        }

        if (!this.chess.gameOver) this.startColumnScan();
    }

    /**
     * AI 移动
     */
    async aiMove() {
        const generation = this.gameGeneration;
        this.isAIThinking = true;
        this.infoDisplay.updateAIThinking('AI正在思考最佳走法...');
        
        try {
            // 获取 AI 的最佳移动
            const aiMove = await this.ai.getBestMove();

            // 新游戏可能发生在异步等待期间，旧棋局的 AI 结果不得落到新棋盘上。
            if (generation !== this.gameGeneration) return;
            
            if (!aiMove) {
                this.infoDisplay.updateAIThinking('AI 无法移动，游戏结束');
                this.handleGameOver();
                return;
            }
            
            // 生成思考过程
            const thinking = this.ai.generateThinkingProcess(aiMove);
            this.infoDisplay.updateAIThinking(`AI分析：${thinking}`);
            
            // 执行 AI 移动
            const moveResult = this.chess.makeMove(
                aiMove.from.x, 
                aiMove.from.y, 
                aiMove.to.x, 
                aiMove.to.y
            );
            
            // 播放音效
            if (moveResult.captured) {
                this.audioManager.playCaptureSound();
            } else {
                this.audioManager.playMoveSound();
            }
            
            // 更新显示
            this.renderer.setLastMove(moveResult);
            
            const moveNumber = Math.floor(this.chess.moveHistory.length / 2);
            this.infoDisplay.addMoveToHistory(moveNumber, this.formatMove(moveResult), moveResult.isRed);
            
            this.updateDisplay();
            
            // 检查游戏是否结束
            if (this.chess.gameOver) {
                this.handleGameOver();
            }
            
        } catch (error) {
            console.error('AI 移动错误:', error);
            this.infoDisplay.updateAIThinking('AI思考出错，请重新开始游戏');
        } finally {
            if (generation === this.gameGeneration) this.isAIThinking = false;
        }
    }

    /**
     * 更新显示
     */
    updateDisplay() {
        this.renderer.render(this.chess.board, this.chess);
        this.infoDisplay.updateCurrentTurn(this.chess.currentPlayer);
        this.infoDisplay.updateMoveCount(this.chess.moveHistory.length);
        
        if (this.chess.gameOver) {
            const status = this.chess.endReason === 'checkmate'
                ? '将死'
                : this.chess.endReason === 'stalemate'
                    ? '困毙'
                    : this.chess.endReason === 'repetition' ? '重复和棋' : '游戏结束';
            this.infoDisplay.updateGameStatus(status);
        } else if (this.chess.isInCheck(this.chess.currentPlayer === 'red')) {
            this.infoDisplay.updateGameStatus('将军');
        } else {
            this.infoDisplay.updateGameStatus('进行中');
        }
        this.renderScanFocus();
    }

    /**
     * 新游戏
     */
    newGame() {
        this.gameGeneration++;
        this.hideActionConfirmModal();
        this.boardScanSnapshot = null;
        this.audioManager.playNewGameSound();
        
        this.chess.reset();
        this.renderer.clearSelection();
        this.renderer.setLastMove(null);
        this.infoDisplay.clearMoveHistory();
        this.infoDisplay.updateAIThinking('点击棋子开始走棋...');
        
        this.redTime = 900;
        this.blackTime = 900;
        this.infoDisplay.updateTimer('red', this.timedMode ? this.redTime : null);
        this.infoDisplay.updateTimer('black', this.timedMode ? this.blackTime : null);
        
        this.isAIThinking = false;
        
        this.updateDisplay();
        this.startTimer();
        this.startColumnScan();
    }

    /**
     * 悔棋
     */
    undoMove() {
        // 检查游戏状态
        if (this.isAIThinking || this.chess.gameOver) {
            console.log('无法悔棋：游戏状态不允许');
            this.startColumnScan();
            return;
        }
        
        // 调用 chess.js 中的 undoMove 方法（会撤销两步）
        const result = this.chess.undoMove();
        
        if (!result) {
            console.log('悔棋失败');
            this.startColumnScan();
            return;
        }
        
        // 播放悔棋音效
        this.audioManager.playUndoSound();
        
        // 清除选择和最后一步标记
        this.renderer.clearSelection();
        this.renderer.setLastMove(null);
        
        // 重新构建走法历史显示
        this.rebuildMoveHistory();
        
        // 更新显示
        this.updateDisplay();
        this.startColumnScan();
        
        console.log('悔棋成功');
    }

    /**
     * 重新构建走法历史显示
     */
    rebuildMoveHistory() {
        this.infoDisplay.clearMoveHistory();
        
        const moves = this.chess.moveHistory;
        for (let i = 0; i < moves.length; i++) {
            const moveNumber = Math.floor(i / 2) + 1;
            const isRed = i % 2 === 0;
            const moveText = this.formatMove(moves[i]);
            this.infoDisplay.addMoveToHistory(moveNumber, moveText, isRed);
        }
    }

    setScanInterval(intervalMs) {
        const parsed = Number(intervalMs);
        if (!Number.isFinite(parsed)) return;
        this.scanIntervalMs = Math.max(1100, Math.min(3000, Math.round(parsed)));
        this.restartScanTimer();
    }

    canScan() {
        return !this.chess.gameOver && !this.isAIThinking && this.chess.currentPlayer === 'red';
    }

    startColumnScan() {
        if (!this.canScan()) {
            this.pauseBciScan(this.chess.gameOver ? '棋局已结束' : '等待 AI 落子');
            return;
        }

        this.selectedColumn = null;
        this.navigationLevel = ScanPhase.COLUMN;
        this.pendingConfirmedAction = null;
        this.boardScanSnapshot = null;
        this.hideActionConfirmModal();
        this.renderer.clearSelection();
        const columns = [];
        for (let x = 0; x < 9; x++) {
            if (this.chess.board.some(row => row[x] && this.chess.isRed(row[x]))) {
                columns.push({kind: 'column', x});
            }
        }
        this.scanCursor.setPhase(
            ScanPhase.COLUMN,
            boardScanCandidates(columns, this.scanCursor.direction)
        );
        this.syncNavigationButtons();
        this.restartScanTimer();
        this.updateDisplay();
    }

    startRowScan(column) {
        this.selectedColumn = column;
        this.navigationLevel = ScanPhase.ROW;
        this.boardScanSnapshot = null;
        this.hideActionConfirmModal();
        this.renderer.clearSelection();
        const pieces = [];
        for (let y = 0; y < 10; y++) {
            const piece = this.chess.getPiece(column, y);
            if (piece && this.chess.isRed(piece)) pieces.push({kind: 'piece', x: column, y, piece});
        }
        if (pieces.length === 0) {
            this.startColumnScan();
            return;
        }
        this.scanCursor.setPhase(
            ScanPhase.ROW,
            boardScanCandidates(pieces, this.scanCursor.direction)
        );
        this.syncNavigationButtons();
        this.restartScanTimer();
        this.updateDisplay();
    }

    startTargetScan(x, y) {
        this.selectedColumn = x;
        this.navigationLevel = ScanPhase.TARGET;
        this.boardScanSnapshot = null;
        this.hideActionConfirmModal();
        const legalMoves = this.chess.getLegalMoves(x, y)
            .slice()
            .sort((a, b) => a.y - b.y || a.x - b.x)
            .map(move => ({kind: 'move', x: move.x, y: move.y}));
        this.scanCursor.setPhase(
            ScanPhase.TARGET,
            boardScanCandidates(legalMoves, this.scanCursor.direction)
        );
        this.syncNavigationButtons();
        this.restartScanTimer();
        this.renderScanFocus();
    }

    startMenuScan() {
        this.hideActionConfirmModal();
        if (this.scanCursor.phase !== ScanPhase.MENU && this.scanCursor.phase !== ScanPhase.CONFIRM) {
            this.boardScanSnapshot = this.scanCursor.snapshot();
        }
        this.scanCursor.setPhase(
            ScanPhase.MENU,
            operationScanCandidates(this.navigationLevel, this.scanCursor.direction)
        );
        this.syncNavigationButtons();
        this.restartScanTimer();
        this.updateDisplay();
    }

    startConfirmScan(action) {
        this.pendingConfirmedAction = action;
        this.showActionConfirmModal(action);
        this.scanCursor.setPhase(ScanPhase.CONFIRM, [
            {kind: 'cancel', label: '返回'},
            {kind: 'confirm', label: '确定'}
        ], 'confirm');
        this.restartScanTimer();
        this.renderScanFocus();
    }

    pauseBciScan(message = '扫描已暂停') {
        this.scanCursor.pause();
        if (this.scanTimer) window.clearInterval(this.scanTimer);
        this.scanTimer = null;
        this.clearScanFocus();
        const phaseEl = document.getElementById('scanPhase');
        const choiceEl = document.getElementById('scanChoice');
        if (phaseEl) phaseEl.textContent = '脑电扫描暂停';
        if (choiceEl) choiceEl.textContent = message;
        this.syncNavigationButtons();
    }

    syncNavigationButtons() {
        const scanningAvailable = this.canScan() && this.scanCursor.phase !== ScanPhase.PAUSED;
        const canReturnSelection = scanningAvailable
            && this.scanCursor.phase === ScanPhase.MENU
            && this.boardScanSnapshot !== null;
        const canReturnColumn = scanningAvailable && (
            this.navigationLevel === ScanPhase.ROW || this.navigationLevel === ScanPhase.TARGET
        );
        const canReturnRow = scanningAvailable && this.navigationLevel === ScanPhase.TARGET;
        document.getElementById('returnSelectionBtn').disabled = !canReturnSelection;
        document.getElementById('returnColumnBtn').disabled = !canReturnColumn;
        document.getElementById('returnRowBtn').disabled = !canReturnRow;
    }

    restartScanTimer() {
        if (this.scanTimer) window.clearInterval(this.scanTimer);
        this.scanTimer = null;
        if (!this.canScan() || this.scanCursor.phase === ScanPhase.PAUSED) return;
        this.scanTimer = window.setInterval(() => {
            this.scanCursor.advance();
            this.renderScanFocus();
        }, this.scanIntervalMs);
    }

    handleBciAction(action) {
        // 棋局结束弹窗只有一个操作，咬牙直接等同于点击“开始新游戏”。
        if (this.chess.gameOver) {
            if (action === 'bite') {
                this.gameOverModal.hide();
                this.newGame();
            }
            return;
        }
        if (!this.canScan()) return;
        if (action === 'look_left' || action === 'look_right') {
            this.scanCursor.setDirectionFromAction(action);
            this.restartScanTimer();
            this.renderScanFocus();
            return;
        }
        if (action !== 'bite') return;

        const item = this.scanCursor.current();
        if (!item) return;
        this.activateScanItem(item);
    }

    activateScanItem(item) {
        switch (this.scanCursor.phase) {
            case ScanPhase.COLUMN:
                if (item.kind === 'menu') this.startMenuScan();
                if (item.kind === 'column') this.startRowScan(item.x);
                break;
            case ScanPhase.ROW:
                if (item.kind === 'menu') this.startMenuScan();
                if (item.kind === 'piece') {
                    this.audioManager.playSelectSound();
                    this.renderer.setSelectedPiece(item.x, item.y);
                    this.renderer.setLegalMoves(this.chess.getLegalMoves(item.x, item.y));
                    this.updateDisplay();
                    this.startTargetScan(item.x, item.y);
                }
                break;
            case ScanPhase.TARGET:
                if (item.kind === 'menu') this.startMenuScan();
                if (item.kind === 'move') this.handleMoveClick(item.x, item.y);
                break;
            case ScanPhase.MENU:
                if (item.kind === 'return_selection') {
                    this.returnToBoardSelection();
                } else if (item.kind === 'back_column') {
                    this.startColumnScan();
                } else if (item.kind === 'back_row') {
                    this.startRowScan(this.selectedColumn);
                } else if (item.kind === 'new_game' || item.kind === 'exit') {
                    this.startConfirmScan(item.kind);
                } else if (item.kind === 'undo') {
                    this.undoMove();
                } else if (item.kind === 'sound') {
                    this.toggleSound();
                    this.startColumnScan();
                }
                break;
            case ScanPhase.CONFIRM:
                if (item.kind === 'cancel') {
                    this.cancelPendingAction();
                } else if (item.kind === 'confirm') {
                    this.confirmPendingAction();
                }
                break;
        }
    }

    clearScanFocus() {
        document.querySelectorAll('.bci-piece-focus, .bci-target-focus, .bci-control-focus, .bci-group-focus')
            .forEach(element => element.classList.remove(
                'bci-piece-focus', 'bci-target-focus', 'bci-control-focus', 'bci-group-focus'
            ));
        document.querySelectorAll('.scan-board-arrow').forEach(element => element.remove());
        document.getElementById('bciScanPanel')?.classList.remove('bci-virtual-focus');
    }

    renderScanFocus() {
        this.clearScanFocus();
        const phaseEl = document.getElementById('scanPhase');
        const choiceEl = document.getElementById('scanChoice');
        const directionEl = document.getElementById('scanDirection');
        const item = this.scanCursor.current();
        if (!phaseEl || !choiceEl || !directionEl || !item) return;

        directionEl.textContent = this.scanCursor.direction < 0 ? '向左轮转' : '向右轮转';
        const board = document.getElementById('chessboard');

        if (this.scanCursor.phase === ScanPhase.COLUMN) {
            phaseEl.textContent = '选择路';
            if (item.kind === 'menu') {
                choiceEl.textContent = '棋局操作';
                document.querySelector('.control-row')?.classList.add('bci-group-focus');
            } else {
                choiceEl.textContent = `第 ${9 - item.x} 路`;
                const arrow = document.createElement('div');
                arrow.className = 'scan-board-arrow scan-column-arrow';
                arrow.textContent = '↑';
                arrow.style.left = `${this.renderer.padding + item.x * this.renderer.cellSize}px`;
                arrow.style.top = `${this.renderer.padding + 9 * this.renderer.cellSize + 52}px`;
                board.appendChild(arrow);
            }
            return;
        }

        if (this.scanCursor.phase === ScanPhase.ROW) {
            phaseEl.textContent = '选择行与棋子';
            if (item.kind === 'menu') {
                choiceEl.textContent = '棋局操作';
                document.querySelector('.control-row')?.classList.add('bci-group-focus');
            } else {
                choiceEl.textContent = `第 ${10 - item.y} 行 · ${this.pieceLabel(item.piece)}`;
                document.querySelector(`.chess-piece[data-x="${item.x}"][data-y="${item.y}"]`)
                    ?.classList.add('bci-piece-focus');
                const arrow = document.createElement('div');
                arrow.className = 'scan-board-arrow scan-row-arrow';
                arrow.textContent = '→';
                arrow.style.left = '-10px';
                arrow.style.top = `${this.renderer.padding + item.y * this.renderer.cellSize}px`;
                board.appendChild(arrow);
            }
            return;
        }

        if (this.scanCursor.phase === ScanPhase.TARGET) {
            phaseEl.textContent = '选择落点';
            if (item.kind === 'menu') {
                choiceEl.textContent = '棋局操作';
                document.querySelector('.control-row')?.classList.add('bci-group-focus');
            } else {
                choiceEl.textContent = `落到第 ${9 - item.x} 路、第 ${10 - item.y} 行`;
                document.querySelector(`.move-hint[data-x="${item.x}"][data-y="${item.y}"]`)
                    ?.classList.add('bci-target-focus');
            }
            return;
        }

        if (this.scanCursor.phase === ScanPhase.MENU) {
            phaseEl.textContent = '功能选择';
            choiceEl.textContent = item.label;
            const idByKind = {
                return_selection: 'returnSelectionBtn',
                back_column: 'returnColumnBtn', back_row: 'returnRowBtn',
                new_game: 'newGameBtn', undo: 'undoBtn', sound: 'soundBtn', exit: 'exitBtn'
            };
            document.getElementById(idByKind[item.kind])?.classList.add('bci-control-focus');
            return;
        }

        if (this.scanCursor.phase === ScanPhase.CONFIRM) {
            phaseEl.textContent = '等待确认';
            choiceEl.textContent = item.label;
            const buttonId = item.kind === 'confirm' ? 'confirmActionBtn' : 'cancelActionBtn';
            document.getElementById(buttonId)?.classList.add('bci-control-focus');
        }
    }

    returnToBoardSelection() {
        const snapshot = this.boardScanSnapshot;
        if (!snapshot) return;
        this.pendingConfirmedAction = null;
        this.hideActionConfirmModal();
        this.boardScanSnapshot = null;
        this.scanCursor.restore(snapshot);
        this.navigationLevel = snapshot.phase;
        this.syncNavigationButtons();
        this.restartScanTimer();
        this.updateDisplay();
    }

    showActionConfirmModal(action) {
        const message = action === 'new_game'
            ? '确定要开始新游戏吗？'
            : '确定要退出中国象棋游戏吗？';
        document.getElementById('actionConfirmMessage').textContent = message;
        document.getElementById('actionConfirmModal').classList.remove('hidden');
    }

    hideActionConfirmModal() {
        document.getElementById('actionConfirmModal')?.classList.add('hidden');
    }

    cancelPendingAction() {
        this.pendingConfirmedAction = null;
        this.hideActionConfirmModal();
        this.startMenuScan();
    }

    confirmPendingAction() {
        const action = this.pendingConfirmedAction;
        if (!action) return;
        this.pendingConfirmedAction = null;
        this.hideActionConfirmModal();
        if (action === 'new_game') this.newGame();
        if (action === 'exit') this.requestExit();
    }

    pieceLabel(piece) {
        return ({K: '帅', A: '仕', B: '相', N: '马', R: '车', C: '炮', P: '兵'})[piece] || piece;
    }

    requestExit() {
        this.hideActionConfirmModal();
        this.pauseBciScan('正在退出');
        if (window.InputDsChessHost?.requestExit) {
            window.InputDsChessHost.requestExit();
        }
    }
    
    /**
     * 切换音效
     */
    toggleSound() {
        const enabled = this.audioManager.toggle();
        const soundBtn = document.getElementById('soundBtn');
        const icon = soundBtn.querySelector('.sound-icon');
        
        if (enabled) {
            icon.textContent = '🔊';
            soundBtn.classList.remove('opacity-50');
            this.audioManager.playSelectSound();
        } else {
            icon.textContent = '🔇';
            soundBtn.classList.add('opacity-50');
        }
    }

    /**
     * 格式化移动为文本
     */
    formatMove(move) {
        const pieceNames = {
            'K': '帅', 'k': '将',
            'A': '仕', 'a': '士',
            'B': '相', 'b': '象',
            'N': '马', 'n': '马',
            'R': '车', 'r': '车',
            'C': '炮', 'c': '炮',
            'P': '兵', 'p': '卒'
        };
        
        const pieceName = pieceNames[move.piece] || move.piece;
        const fromPos = `(${move.from.x},${move.from.y})`;
        const toPos = `(${move.to.x},${move.to.y})`;
        
        return `${pieceName}${fromPos}→${toPos}`;
    }

    /**
     * 处理游戏结束
     */
    handleGameOver() {
        this.stopTimer();
        
        // 播放游戏结束音效
        if (this.chess.winner === 'red') {
            this.audioManager.playVictorySound();
        } else if (this.chess.winner === 'black') {
            this.audioManager.playDefeatSound();
        } else {
            this.audioManager.playDrawSound();
        }
        
        if (this.chess.winner) {
            this.gameOverModal.show(this.chess.winner, this.chess.endReason);
        } else {
            this.gameOverModal.show('draw', this.chess.endReason);
        }
        
        this.updateDisplay();
    }

    /**
     * 启动计时器
     */
    startTimer() {
        this.stopTimer();

        if (!this.timedMode) {
            this.infoDisplay.updateTimer('red', null);
            this.infoDisplay.updateTimer('black', null);
            return;
        }
        
        this.timerInterval = setInterval(() => {
            if (this.chess.gameOver) {
                this.stopTimer();
                return;
            }
            
            if (this.chess.currentPlayer === 'red' && !this.isAIThinking) {
                this.redTime--;
                this.infoDisplay.updateTimer('red', this.redTime);
                
                if (this.redTime <= 0) {
                    this.chess.gameOver = true;
                    this.chess.winner = 'black';
                    this.handleGameOver();
                }
            } else if (this.chess.currentPlayer === 'black' || this.isAIThinking) {
                // AI思考时也要计时黑方
                this.blackTime--;
                this.infoDisplay.updateTimer('black', this.blackTime);
                
                if (this.blackTime <= 0) {
                    this.chess.gameOver = true;
                    this.chess.winner = 'red';
                    this.handleGameOver();
                }
            }
        }, 1000);
    }

    /**
     * 停止计时器
     */
    stopTimer() {
        if (this.timerInterval) {
            clearInterval(this.timerInterval);
            this.timerInterval = null;
        }
    }
}

// 启动游戏
document.addEventListener('DOMContentLoaded', () => {
    const controller = new GameController();
    window.inputDsChess = {
        handleAction: action => controller.handleBciAction(action),
        setScanInterval: intervalMs => controller.setScanInterval(intervalMs)
    };
    window.addEventListener('beforeunload', () => controller.pauseBciScan('页面关闭'));
});
