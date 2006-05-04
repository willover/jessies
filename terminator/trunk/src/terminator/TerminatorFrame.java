package terminator;

import e.gui.*;
import e.util.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.*;
import terminator.view.*;

public class TerminatorFrame extends JFrame {
	private Dimension terminalSize;
	private JTabbedPane tabbedPane;
	
	private ArrayList<JTerminalPane> terminals;
	
	private Timer terminalSizeTimer;
	
	private final Color originalBackground = getBackground();
	
	public TerminatorFrame(List<JTerminalPane> initialTerminalPanes) {
		super("Terminator");
		terminals = new ArrayList<JTerminalPane>(initialTerminalPanes);
		initFrame();
		initFocus();
		for (JTerminalPane terminal : terminals) {
			terminal.start();
		}
	}
	
	/**
	 * Quantizes any resize of the frame so that our terminals have an
	 * integer number of rows and columns. X11 provides direct support
	 * for communicating this desire to the window manager, but we can't
	 * use that, and it wouldn't work on other OSes anyway.
	 * 
	 * This code is based on hack #33 "Window Snapping" from O'Reilly's
	 * book "Swing Hacks". (Their hack doesn't actually work, at least on
	 * Mac OS.)
	 */
	private void initSizeMonitoring() {
		class SizeMonitor extends ComponentAdapter {
			private Dimension lastSize;
			private boolean syntheticResize = false;
			
			@Override
			public void componentShown(ComponentEvent e) {
				lastSize = getSize();
			}
			
			@Override
			public void componentResized(ComponentEvent e) {
				if (lastSize == null || syntheticResize) {
					return;
				}
				Dimension charSize = terminals.get(0).getTextPane().getCharUnitSize();
				Dimension suggestedSize = getSize();
				Dimension newSize = new Dimension();
				newSize.width = suggestedSize.width - (suggestedSize.width - lastSize.width) % charSize.width;
				newSize.height = suggestedSize.height - (suggestedSize.height - lastSize.height) % charSize.height;
				syntheticResize = true;
				setSize(newSize);
				syntheticResize = false;
			}
		}
		addComponentListener(new SizeMonitor());
	}
	
	public void updateFrameTitle() {
		StringBuilder title = new StringBuilder();
		if (terminalSize != null) {
			title.append("[").append(terminalSize.width).append(" x ").append(terminalSize.height).append("] ");
		}
		if (terminals.size() >= 1) {
			JTerminalPane pane;
			if (tabbedPane == null) {
				pane = terminals.get(0);
			} else {
				pane = (JTerminalPane) tabbedPane.getSelectedComponent();
			}
			if (pane != null) {
				title.append(pane.getName());
			}
		}
		setTitle(title.toString());
	}
	
	private void initFrame() {
		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // Misnomer: we add our own WindowListener.
		
		JFrameUtilities.setFrameIcon(this);
		
		if (Options.getSharedInstance().shouldUseMenuBar()) {
			setJMenuBar(new TerminatorMenuBar());
		}
		Terminator.getSharedInstance().getFrames().addFrame(this);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(WindowEvent event) {
				Terminator.getSharedInstance().getFrames().frameStateChanged();
			}
			
			@Override
			public void windowClosing(WindowEvent event) {
				handleWindowCloseRequestFromUser();
			}
			
			@Override
			public void windowIconified(WindowEvent event) {
				Terminator.getSharedInstance().getFrames().frameStateChanged();
			}
			
			@Override
			public void windowDeiconified(WindowEvent event) {
				Terminator.getSharedInstance().getFrames().frameStateChanged();
			}
		});
		initTerminals();
		pack();
		setVisible(true);
		initSizeMonitoring();
		
		WindowMenu.getSharedInstance().addWindow(this);
		GuiUtilities.finishGnomeStartup();
	}
	
	private void initTerminals() {
		// We always have one terminal...
		switchToSinglePane();
		
		// And sometimes we have more...
		for (int i = 1; i < terminals.size(); ++i) {
			addPaneToUI(terminals.get(i));
		}
	}
	
	/**
	 * Give focus to the first terminal.
	 */
	private void initFocus() {
		terminals.get(0).requestFocus();
	}
	
	/**
	 * Switches to a tabbed-pane UI where we can have one tab per terminal.
	 */
	private void switchToTabbedPane() {
		if (tabbedPane != null) {
			return;
		}
		
		setBackground(originalBackground);
		
		JComponent oldContentPane = (JComponent) getContentPane();
		Dimension initialSize = oldContentPane.getSize();
		
		tabbedPane = new JTabbedPane();
		tabbedPane.addChangeListener(new TerminalFocuser());
		ComponentUtilities.disableFocusTraversal(tabbedPane);
		
		EPopupMenu tabMenu = new EPopupMenu(tabbedPane);
		tabMenu.addMenuItemProvider(new MenuItemProvider() {
			public void provideMenuItems(MouseEvent e, Collection<Action> actions) {
				// If the user clicked on some part of the tabbed pane that isn't actually a tab, we're not interested.
				int tabIndex = tabbedPane.indexAtLocation(e.getX(), e.getY());
				if (tabIndex == -1) {
					return;
				}
				
				actions.add(new TerminatorMenuBar.NewTabAction());
				actions.add(new TerminatorMenuBar.DetachTabAction());
				actions.add(null);
				actions.add(new TerminatorMenuBar.CloseAction());
			}
		});
		
		// The tabs themselves (the components with the labels)
		// shouldn't be able to get the focus. If they can, clicking
		// on an already-selected tab takes focus away from the
		// associated terminal, which is annoying.
		tabbedPane.setFocusable(false);
		
		if (oldContentPane instanceof JTerminalPane) {
			addPaneToUI((JTerminalPane) oldContentPane);
		}
		
		setContentPane(tabbedPane);
		validate();
		
		Dimension finalSize = oldContentPane.getSize();
		fixTerminalSizesAfterAddingOrRemovingTabbedPane(initialSize, finalSize);
	}
	
	/**
	 * Switches to a simple UI where we can have only one terminal.
	 */
	private void switchToSinglePane() {
		setBackground(Options.getSharedInstance().getColor("background"));
		
		JTerminalPane soleSurvivor = terminals.get(0);
		Dimension initialSize = soleSurvivor.getSize();
		
		soleSurvivor.invalidate();
		setContentPane(soleSurvivor);
		validate();
		
		soleSurvivor.requestFocus();
		tabbedPane = null;
		
		Dimension finalSize = getContentPane().getSize();
		fixTerminalSizesAfterAddingOrRemovingTabbedPane(initialSize, finalSize);
		
		updateFrameTitle();
	}
	
	/**
	 * Ensures that when we change tab, we give focus to that terminal.
	 */
	private class TerminalFocuser implements ChangeListener {
		public void stateChanged(ChangeEvent e) {
			final Component selected = tabbedPane.getSelectedComponent();
			if (selected != null) {
				EventQueue.invokeLater(new Runnable() {
					public void run() {
						selected.requestFocus();
					}
				});
			}
			updateFrameTitle();
		}
	}

	/**
	 * Increases the size of the frame based on the amount of space taken
	 * away from the terminal to insert the tabbed pane. The end result
	 * should be that the terminal size remains constant but the window
	 * grows.
	 */
	private void fixTerminalSizesAfterAddingOrRemovingTabbedPane(Dimension initialSize, Dimension finalSize) {
		Dimension size = getSize();
		size.height += (initialSize.height - finalSize.height);
		size.width += (initialSize.width - finalSize.width);
		setSize(size);
	}
	
	public int getTerminalPaneCount() {
		if (tabbedPane == null) {
			return 1;
		}
		return tabbedPane.getTabCount();
	}
	
	public void detachCurrentTab() {
		JTerminalPane escapee = (JTerminalPane) tabbedPane.getSelectedComponent();
		terminals.remove(escapee);
		closeTab(escapee);
		Terminator.getSharedInstance().openFrame(escapee);
	}
	
	public void cycleTab(int delta) {
		if (tabbedPane != null) {
			int tabCount = tabbedPane.getTabCount();
			tabbedPane.setSelectedIndex((tabbedPane.getSelectedIndex() + delta + tabCount) % tabCount);
		}
	}
	
	public void setSelectedTabIndex(int index) {
		int tabCount = tabbedPane.getTabCount();
		if (index < tabCount) {
			tabbedPane.setSelectedIndex(index);
		}
	}

	/**
	 * Removes the given terminal. If this is the last terminal, close
	 * the window.
	 */
	public void closeTerminalPane(JTerminalPane victim) {
		terminals.remove(victim);
		if (tabbedPane != null) {
			closeTab(victim);
		} else {
			setVisible(false);
		}
	}
	
	/**
	 * Removes the given terminal when there's more than one terminal in the frame.
	 */
	private void closeTab(JTerminalPane victim) {
		tabbedPane.remove(victim);
		if (tabbedPane.getTabCount() == 0) {
			// FIXME: how can we ever get here?
			setVisible(false);
		} else if (tabbedPane.getTabCount() == 1) {
			switchToSinglePane();
		} else {
			// Just hand focus to the visible tab's terminal. We
			// do this later because otherwise Swing seems to give
			// the focus to the tab itself, rather than the
			// component on the tab.
			EventQueue.invokeLater(new Runnable() {
				public void run() {
					if (tabbedPane == null) {
						return;
					}
					Component terminal = tabbedPane.getSelectedComponent();
					if (terminal == null) {
						return;
					}
					terminal.requestFocus();
				}
			});
		}
	}
	
	public void handleWindowCloseRequestFromUser() {
		// We can't iterate over "terminals" directly because we're causing terminals to close and be removed from the list.
		ArrayList<JTerminalPane> copyOfTerminals = new ArrayList<JTerminalPane>(terminals);
		for (JTerminalPane terminal : copyOfTerminals) {
			if (tabbedPane != null) {
				tabbedPane.setSelectedComponent(terminal);
			}
			if (terminal.doCheckedCloseAction() == false) {
				// If the user hit "Cancel" for one terminal, cancel the close for all other terminals in the same window.
				return;
			}
		}
	}
	
	/**
	 * Tidies up after the frame has been hidden.
	 * We can't use a ComponentListener because that's invoked on the EDT, as is handleQuit, which relies on us tidying up while it goes.
	 */
	@Override
	public void setVisible(boolean newState) {
		super.setVisible(newState);
		if (newState == false) {
			for (JTerminalPane terminal : terminals) {
				terminal.destroyProcess();
			}
			dispose();
			Terminator.getSharedInstance().getFrames().removeFrame(this);
		}
	}
	
	public void setTerminalSize(Dimension size) {
		this.terminalSize = size;
		updateFrameTitle();
		if (terminalSizeTimer != null) {
			terminalSizeTimer.stop();
		}
		terminalSizeTimer = new Timer(2000, new ActionListener() {
			public void actionPerformed(ActionEvent event) {
				terminalSizeTimer = null;
				terminalSize = null;
				updateFrameTitle();
			}
		});
		terminalSizeTimer.setRepeats(false);
		terminalSizeTimer.start();
	}
	
	public void terminalNameChanged(JTerminalPane terminal) {
		if (tabbedPane != null) {
			int index = tabbedPane.indexOfComponent(terminal);
			tabbedPane.setTitleAt(index, terminal.getName());
		}
		updateFrameTitle();
	}
	
	public void addTab(JTerminalPane newPane) {
		terminals.add(newPane);
		addPaneToUI(newPane);
		newPane.start();
		tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 1);
		updateFrameTitle();
	}
	
	private void addPaneToUI(JTerminalPane newPane) {
		switchToTabbedPane();
		// FIXME: in 6.0, Sun 4499556 "Use arbitrary (J)Components as
		// JTabbedPane tab labels" is fixed, so we can improve this to
		// add newPane.getOutputSpinner() using setTabComponentAt.
		tabbedPane.addTab(newPane.getName(), newPane);
	}
}
