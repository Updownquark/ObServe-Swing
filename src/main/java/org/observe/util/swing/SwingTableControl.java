package org.observe.util.swing;

import java.awt.EventQueue;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

import javax.swing.JFrame;

import org.observe.Observable;
import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.collect.ObservableCollection;
import org.qommons.TableContentControl;
import org.qommons.collect.CollectionElement;
import org.qommons.fn.FunctionUtils;
import org.qommons.io.Format;
import org.qommons.threading.QommonsTimer;

public class SwingTableControl {

	/** A sample tooltip to use for a text box whose value is a {@link TableContentControl} */
	public static final String TABLE_CONTROL_TOOLTIP = "<html>Enter text, numbers, or dates to filter the table rows.<br>\n"//
		+ "Use <b>XXX-XXX</b> to enter a range of numbers or dates.<br>\n"//
		+ "Use <b>abc*efg</b> to match any text with \"efg\" occurring after \"abc\".<br>\n"//
		+ "Use <b>\\XXX</b> to search via regular expressions.<br>\n"//
		+ "Use <b>sort:column1,column2</b> to sort the table rows.<br>\n"//
		+ "Use <b>columns:column1,column2</b> to sort the table columns.<br>\n"//
		+ "All filters are AND-ed.\n"//
		+ "</html>";

	/**
	 * Does some standard configuration on a text field for parsing {@link TableContentControl}s
	 *
	 * @param <F> The sub-type of field
	 * @param field The field to configure
	 * @param commitOnType Whether the text field should fire its new value each time the user types anything, or only when the user presses
	 *        enter or de-focuses the field
	 * @return The field
	 */
	public static <F extends PanelPopulation.ComponentEditor<ObservableTextField<TableContentControl>, ?>> F configureSearchField(F field,
		boolean commitOnType) {
		if (field.getEditor().getValue().get() == null)
			field.getEditor().getValue().set(TableContentControl.DEFAULT, null);
		return (F) field.fill().withTooltip(TABLE_CONTROL_TOOLTIP).modifyEditor(tf2 -> tf2//
			.setCommitOnType(commitOnType).setEmptyText("Search or Sort...")
			.setIcon(ObservableSwingUtils.getFixedIcon(null, "/icons/search.png", 16, 16)));
	}

	/**
	 *
	 * @param <E> The type of rows in the table
	 * @param values The unfiltered rows for the table
	 * @param render Supplies a renderer for each column in the table
	 * @param filter The {@link TableContentControl} control value to apply
	 * @param until An observable to release the subscriptions that this method creates
	 * @return The filtered rows for the table
	 */
	public static <E> ObservableCollection<TableContentControl.FilteredValue<E>> applyRowControl(ObservableCollection<E> values,
		Supplier<? extends Collection<? extends TableContentControl.ValueRenderer<? super E>>> render, ObservableValue<? extends TableContentControl> filter,
			Observable<?> until) {
		List<TableContentControl.ValueRenderer<? super E>> rendererList = new ArrayList<>();
		/*int[] tests = new int[2]; // DEBUGGING
		int[] compares = new int[2];
		Causable.CausableKey key = Causable.key((cause, __) -> {
			System.out.println((tests[0] + tests[1]) + " values tested, " + tests[0] + " passed");
			System.out.println((compares[0] + compares[1]) + " comparisons, " + compares[0] + " reordered");
			tests[0] = tests[1] = 0;
			compares[0] = compares[1] = 0;
		});
		filter.changes().act(evt -> {
			evt.getRootCausable().onFinish(key);
		});*/
		return values.flow().<TableContentControl.FilteredValue<E>> transform( //
			combine -> combine.combineWith(filter).build(FunctionUtils.printableBiFn((x, cv) -> {
				Collection<? extends TableContentControl.ValueRenderer<? super E>> renders = render.get();
				int i = 0;
				for (TableContentControl.ValueRenderer<? super E> r : renders) {
					if (i == rendererList.size())
						rendererList.add(r);
					else
						rendererList.set(i, r);
					i++;
				}
				while (rendererList.size() > i)
					rendererList.remove(rendererList.size() - 1);
				TableContentControl.FilteredValue<E> v;
				if (cv.hasPreviousResult() && cv.getPreviousResult() != null) {
					v = cv.getPreviousResult();
					v.setValue(x);
				} else
					v = new TableContentControl.FilteredValue<>(x, rendererList.size());
				TableContentControl f = cv.get(filter);
				CharSequence[] texts = new CharSequence[rendererList.size()];
				i = 0;
				for (TableContentControl.ValueRenderer<? super E> r : rendererList)
					texts[i++] = r.render(v.getValue());
				if (f.isSearch()) {
					v.setMatches(f.findMatches(rendererList, texts));
				} else {
					v.setSort();
				}
				// tests[v.hasMatch ? 0 : 1]++;
				return v;
			}, "toFilterValue", null)))//
			.filter(FunctionUtils.printableFn(fv -> (fv != null && fv.hasMatch()) ? null : "No match", "match", null))//
			.sorted(FunctionUtils.printableComparator((fv1, fv2) -> {
				List<String> sorting = filter.get().getRowSorting();
				if (sorting == null || sorting.isEmpty())
					return 0;
				int comp = 0;
				Collection<? extends TableContentControl.ValueRenderer<? super E>> renders = render.get();
				for (String s : sorting) {
					for (TableContentControl.ValueRenderer<? super E> r : renders) {
						int rSort = TableContentControl.RowSorter.sortCategoryMatches(s, r.getName());
						if (rSort != 0) {
							comp = r.compare(fv1.getValue(), fv2.getValue(), rSort < 0);
							break;
						}
					}
					if (comp != 0)
						break;
				}
				// compares[comp <= 0 ? 0 : 1]++;
				return comp;
			}, () -> "rowSorting")).collectActive(until);
	}

	/**
	 * @param <R> The type of rows in the table
	 * @param <C> The type of columns in the table
	 * @param columns The table columns to sort
	 * @param filter The {@link TableContentControl} control value to apply
	 * @param until An observable to release the subscriptions that this method creates
	 * @return The sorted columns to use for the table
	 */
	public static <R, C extends CategoryRenderStrategy<? super R, ?>> ObservableCollection<C> applyColumnControl(
		ObservableCollection<C> columns, ObservableValue<? extends TableContentControl> filter, Observable<?> until) {
		ObservableValue<List<String>> colSorting = filter.transform(tx -> tx.cache(true).map(f -> f == null ? null : f.getColumnSorting()));
		return columns.flow().refresh(colSorting.noInitChanges().filter(evt -> !Objects.equals(evt.getOldValue(), evt.getNewValue())))//
			.sorted(FunctionUtils.printableComparator((c1, c2) -> {
				List<String> columnSorting = colSorting.get();
				if (columnSorting == null)
					return 0;
				for (int c = 0; c < columnSorting.size(); c++) {
					boolean c1Match = TableContentControl.CategoryFilter.categoryMatches(columnSorting.get(c), c1.getName());
					boolean c2Match = TableContentControl.CategoryFilter.categoryMatches(columnSorting.get(c), c2.getName());
					if (c1Match) {
						if (!c2Match)
							return -1;
					} else if (c2Match)
						return 1;
				}
				return 0;
			}, () -> "sortedColumns")).collectActive(until);
	}

	/**
	 * Displays a frame containing a filterable table with pre-populated row and column data. For testing.
	 *
	 * @param args Command-line arguments, ignored
	 */
	public static void main(String... args) {
		SettableValue<TableContentControl> control = SettableValue.<TableContentControl> build().onEdt().withValue(TableContentControl.DEFAULT).build();
		control.noInitChanges().act(evt -> {
			System.out.println(evt.getNewValue());
		});
		ObservableCollection<Map<String, String>> rows = ObservableCollection.<Map<String, String>> build().onEdt().build();
		ObservableCollection<CategoryRenderStrategy<Map<String, String>, String>> columns = ObservableCollection
			.<CategoryRenderStrategy<Map<String, String>, String>> build().onEdt().build();
		SettableValue<Boolean> updating = SettableValue.<Boolean> build().withValue(false).build();
		ObservableCollection<Map<String, String>> selectedRows = ObservableCollection.<Map<String, String>> build().build();
		Random random = new Random();
		QommonsTimer.TaskHandle updateHandle = QommonsTimer.getCommonInstance().build(() -> {
			int updatedRows = random.nextInt(rows.size() / 10);
			for (int r = 0; r < updatedRows; r++) {
				int index = random.nextInt(rows.size());
				CollectionElement<Map<String, String>> row = rows.getElement(index);
				row.get().put("A", "" + random.nextLong());

				rows.mutableElement(row.getElementId()).set(row.get());
			}
		}, Duration.ofSeconds(1), false).onEDT();
		updating.changes().act(evt -> updateHandle.setActive(evt.getNewValue()));
		EventQueue.invokeLater(() -> {
			columns.add(new CategoryRenderStrategy<Map<String, String>, String>("A", String.class, map -> {
				return map.get("A");
			})//
				.formatText(v -> v == null ? "" : v).withMutation(mut -> {
					mut.mutateAttribute((map, v) -> map.put("A", v)).asText(Format.TEXT).withRowUpdate(true);
				}).withWidths(50, 100, 150).withMutation(mut -> mut.mutateAttribute((map, a) -> map.put("A", a)).asText(Format.TEXT)));
			columns.add(new CategoryRenderStrategy<Map<String, String>, String>("B", String.class, map -> {
				return map.get("B");
			})//
				.formatText(v -> v == null ? "" : v).withMutation(mut -> {
					mut.mutateAttribute((map, v) -> map.put("B", v)).asText(Format.TEXT).withRowUpdate(true);
				}).withWidths(50, 100, 150));
			columns.add(new CategoryRenderStrategy<Map<String, String>, String>("C", String.class, map -> {
				return map.get("C");
			})//
				.formatText(v -> v == null ? "" : v).withMutation(mut -> {
					mut.mutateAttribute((map, v) -> map.put("C", v)).asText(Format.TEXT).withRowUpdate(true);
				}).withWidths(50, 150, 550));
			columns.add(new CategoryRenderStrategy<Map<String, String>, String>("D", String.class, map -> {
				return map.get("D");
			})//
				.formatText(v -> v == null ? "" : v).withMutation(mut -> {
					mut.mutateAttribute((map, v) -> map.put("D", v)).asText(Format.TEXT).withRowUpdate(true);
				}).withWidths(50, 100, 150));
			columns.add(new CategoryRenderStrategy<Map<String, String>, String>("OneSAF Type", String.class, map -> {
				return map.get("OneSAF Type");
			})//
				.formatText(v -> v == null ? "" : v).withMutation(mut -> {
					mut.mutateAttribute((map, v) -> map.put("OneSAF Type", v)).asText(Format.TEXT).withRowUpdate(true);
				}).withWidths(50, 100, 150));
			SimpleDateFormat dateFormat = new SimpleDateFormat("EEE MMM dd, yyyy HH:mm:ss");
			Calendar cal = Calendar.getInstance();
			int rowCount = 10_000;
			int lastPct = 0;
			for (int i = 0; i < rowCount; i++) {
				int pct = i * 100 / rowCount;
				if (pct > lastPct) {
					if (pct % 10 == 0)
						System.out.print(pct);
					else
						System.out.print('.');
					System.out.flush();
					lastPct = pct;
				}
				long r = random.nextLong();
				Map<String, String> row = new HashMap<>();
				if (random.nextDouble() < 0.99)
					row.put("A", "" + r);
				if (random.nextDouble() < 0.99)
					row.put("B", Long.toHexString(r));
				if (random.nextDouble() < 0.99) {
					cal.setTimeInMillis(Math.abs(r));
					int year = cal.get(Calendar.YEAR);
					cal.set(Calendar.MILLISECOND, 0);
					if (year > 9999)
						year = year % 10000;
					if (year < 1000)
						year += 1000;
					if (year < 1970)
						year += 1000;
					cal.set(Calendar.YEAR, year);
					row.put("C", dateFormat.format(cal.getTime()));
				}
				if (random.nextDouble() < 0.99) {
					char[] chs = new char[9];
					int mask = 0x7f;
					for (int j = 0; j < chs.length; j++) {
						chs[chs.length - j - 1] = (char) (r & mask);
						if (chs[chs.length - j - 1] < ' ')
							chs[chs.length - j - 1] = ' ';
						r >>>= 7;
					}
					row.put("D", new String(chs));
				}
				int t = (int) Math.round(random.nextDouble() * 6);
				String s;
				switch (t) {
				case 0:
					s = null;
					break;
				case 1:
					s = "";
					break;
				case 2:
					s = "RADAR";
					break;
				case 3:
					s = "JAMMER";
					break;
				case 4:
					s = "IADS";
					break;
				default:
					s = "TELARS";
					break;
				}
				row.put("OneSAF Type", s);
				rows.add(row);
			}
			System.out.println();
			ObservableSwingUtils.buildUI()//
			.systemLandF()//
			.withTitle(TableContentControl.class.getSimpleName() + " Tester")//
			.withSize(640, 900)//
			.withCloseAction(JFrame.EXIT_ON_CLOSE)//
			.withVContent(p -> p.fill().fillV()//
				// .addTextField("Categories:", categories, new Format.ListFormat<>(Format.TEXT, ",", null), f -> f.fill())//
				.<TableContentControl> addTextField("Filter", control, TableContentControl.FORMAT, tf -> configureSearchField(tf.fill(), false))//
				.addTable(rows, table -> {
					table.fill().fillV().withCountTitle("displayed").withItemName("row").fill().withFiltering(control)
					.withSelection(selectedRows)//
					.withColumns(columns)//
					.withAdd(() -> new HashMap<>(), null)//
					;
				})//
				.addHPanel(null, new JustifiedBoxLayout(false).mainCenter(), bp -> bp.addToggleButton(null, updating, "Update", null))//
				.addHPanel(null, new JustifiedBoxLayout(false).mainCenter(), lp -> lp//
					.addLabel(null, selectedRows.observeSize().map(sz -> sz + " row" + (sz == 1 ? "" : "s") + " selected"), Format.TEXT,
						null)//
					)//
				).run(null);
		});
	}

}
